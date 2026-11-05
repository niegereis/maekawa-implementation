/* Estado do plano de Fano a partir dos JSON Lines (LOCKED, RELEASE, BEGIN, END)
   e do modo do experimento (MODE_INIT, MODE_CHANGE, MODE_SETTLED do observador). */
(function (root) {
  "use strict";

  var SEND = { REQUEST: 1, LOCKED: 1, FAILED: 1, INQUIRE: 1, RELINQUISH: 1, RELEASE: 1 };
  // conjuntos de votação por N (Quorum.forSize no Java): S_i = de quem p_i precisa do voto
  var TOPOLOGIES = {
    4: [[0, 1, 2], [0, 1, 3], [0, 2, 3], [1, 2, 3]],                                   // grade 2x2
    7: [[0, 1, 2], [1, 3, 5], [2, 3, 6], [0, 3, 4], [2, 4, 5], [0, 5, 6], [1, 4, 6]]   // plano de Fano
  };
  var SETS = TOPOLOGIES[7];
  function fill(n, v) { var a = []; for (var i = 0; i < n; i++) a.push(v); return a; }
  var OBS = { BEGIN: 1, END: 1, VIOLATION: 1, MODE_INIT: 1, MODE_CHANGE: 1, MODE_SETTLED: 1, CS_CHANGE: 1, COUNTERS_RESET: 1, THINK_CHANGE: 1 };

  function pidOf(name) {
    if (typeof name === "number" && name >= 0) return name;
    var m = /^sync-(\d+)$/.exec(String(name == null ? "" : name));
    return m ? Number(m[1]) : null;
  }

  function keep(e) {
    if (!e || typeof e !== "object") return false;
    var ev = e.event;
    if (e.direction === "send" && SEND[ev]) return true;
    if (e.process === "observer" && OBS[ev]) return true;
    if (ev === "START" && e.n_nodes != null) return true; // START dos nós: diz o N da rodada
    return false;
  }

  /** n: quantos nós a rodada tem (4 ou 7; sem topologia conhecida, usa 7 e marca maxNode). */
  function initialState(n) {
    n = TOPOLOGIES[n] ? n : 7;
    return {
      n: n,
      sets: TOPOLOGIES[n],
      vote: fill(n, null),
      inR: fill(n, false),
      requesting: fill(n, false),
      held: fill(n, null),
      priority: fill(n, null), // {lamport, process_id} do REQUEST
      requestedAt: fill(n, null), // wall_clock do primeiro REQUEST do pedido
      lastWall: null,  // wall_clock do último evento aplicado: o "agora" da tela (ao vivo ou replay)
      violations: 0,           // desde o último "Zerar contadores"
      transitionViolations: 0,
      violationsTotal: 0,      // da rodada inteira (o que o verify.py conta)
      overlap: false,
      sync: null,      // null = log sem eventos de modo (rodada antiga)
      epoch: null,
      settled: true,
      cs: null,        // [min, max] em ms, depois de um CS_CHANGE
      maxNode: -1      // maior id de nó visto; acima de n-1 a rodada não cabe no desenho
    };
  }

  function apply(state, e) {
    var ev = e.event;
    var self = e.process_id != null ? e.process_id : pidOf(e.process);
    var peer = pidOf(e.peer);
    if (e.wall_clock) state.lastWall = e.wall_clock;
    // rodada com mais de 7 nós (ex.: normal-13): registra e não mexe no estado dos 7 do desenho
    var from = pidOf(e.from);
    var big = Math.max(self == null ? -1 : self, peer == null ? -1 : peer, from == null ? -1 : from);
    if (big > state.maxNode) state.maxNode = big;
    if (big >= state.n) return state;
    if (e.direction === "send" && SEND[ev]) {
      if (ev === "REQUEST" && self != null) {
        if (!state.requesting[self]) state.requestedAt[self] = e.wall_clock || null;
        state.requesting[self] = true;
        if (e.priority) state.priority[self] = e.priority;
      }
      if (ev === "LOCKED" && self != null && peer != null) state.vote[self] = peer;
      if ((ev === "RELEASE" || ev === "RELINQUISH") && peer != null) state.vote[peer] = null;
    } else if (e.process === "observer" && ev === "BEGIN") {
      var p = pidOf(e.from);
      if (p != null) {
        state.inR[p] = true;
        state.requesting[p] = false;
        state.requestedAt[p] = null;
        var id = e.request_id;
        state.held[p] = id ? id.client_id + "." + id.seq : "";
      }
      state.overlap = state.inR.filter(Boolean).length > 1;
    } else if (e.process === "observer" && ev === "END") {
      var q = pidOf(e.from);
      if (q != null) {
        state.inR[q] = false;
        state.held[q] = null;
      }
      state.overlap = state.inR.filter(Boolean).length > 1;
    } else if (ev === "VIOLATION") {
      state.violations += 1;
      state.violationsTotal += 1;
      if (e.transition === true) state.transitionViolations += 1;
      state.overlap = true;
    } else if (ev === "MODE_INIT" || ev === "MODE_CHANGE") {
      state.sync = e.sync_enabled === true;
      state.epoch = e.epoch != null ? e.epoch : state.epoch;
      state.settled = ev === "MODE_INIT";
    } else if (ev === "MODE_SETTLED") {
      state.settled = true;
    } else if (ev === "COUNTERS_RESET") {
      state.violations = 0;
      state.transitionViolations = 0;
    } else if (ev === "THINK_CHANGE") {
      state.think = [e.think_min_ms, e.think_max_ms];
    } else if (ev === "CS_CHANGE") {
      state.cs = [e.cs_min_ms, e.cs_max_ms];
    }
    return state;
  }

  /**
   * Próximo a entrar em R (estimativa): entre quem está pedindo e ainda não está em R,
   * o de melhor prioridade (Lamport, id), a mesma regra que os votantes usam.
   * null se ninguém está pedindo.
   */
  function nextUp(state) {
    var q = queue(state);
    return q.length ? q[0] : null;
  }

  function byPriority(a, b) {
    if (a.lamport !== b.lamport) return a.lamport < b.lamport ? -1 : 1;
    return a.process_id - b.process_id;
  }

  /**
   * Fila de pedidos (estimativa, o Maekawa não tem fila global): quem pediu e ainda não entrou
   * em R, com os votos que já tem, de quem ainda espera voto e há quanto tempo pediu. Ordem:
   * só a prioridade (Lamport, id), menor primeiro; o primeiro é o "próximo". Os votos não
   * entram na ordem: um nó junta votos de votantes que os outros nem pedem, então contar
   * votos fazia a fila trocar de lugar a cada LOCKED sem ninguém ter entrado em R.
   */
  function queue(state) {
    var list = [];
    var sets = state.sets;
    for (var pid = 0; pid < state.n; pid++) {
      if (!state.requesting[pid] || state.inR[pid]) continue;
      var waitingOn = [];
      for (var k = 0; k < sets[pid].length; k++) {
        var v = sets[pid][k];
        if (state.vote[v] !== pid) waitingOn.push(v);
      }
      var wait = null;
      if (state.requestedAt[pid] && state.lastWall) {
        wait = Date.parse(state.lastWall) - Date.parse(state.requestedAt[pid]);
        if (!isFinite(wait) || wait < 0) wait = null;
      }
      list.push({
        pid: pid,
        votes: sets[pid].length - waitingOn.length,
        need: sets[pid].length,
        waitingOn: waitingOn,
        priority: state.priority[pid] || { lamport: Infinity, process_id: pid },
        waitMs: wait
      });
    }
    list.sort(function (a, b) { return byPriority(a.priority, b.priority); });
    return list;
  }

  /**
   * Fila local de cada votante, que é o que existe de fato no Maekawa: com quem está o voto
   * e quem espera por ele, na ordem de prioridade (Lamport, id) que o votante usa.
   */
  function voterQueues(state) {
    var q = queue(state);
    var out = [];
    for (var v = 0; v < state.n; v++) {
      var waiting = q.filter(function (r) { return r.waitingOn.indexOf(v) >= 0; })
        .sort(function (a, b) { return byPriority(a.priority, b.priority); });
      out.push({ voter: v, holder: state.vote[v], waiting: waiting });
    }
    return out;
  }

  function at(events, index, n) {
    var s = initialState(n);
    var last = Math.min(index, events.length - 1);
    for (var i = 0; i <= last; i++) apply(s, events[i]);
    return s;
  }

  root.MaekawaReplay = { pidOf: pidOf, keep: keep, initialState: initialState, apply: apply, at: at,
    nextUp: nextUp, queue: queue, voterQueues: voterQueues, SETS: SETS, TOPOLOGIES: TOPOLOGIES,
    /** N da rodada: o n_nodes do START dos nós; logs antigos, sem ele, são de 7 nós (ou do maior id visto). */
    nodesOf: function (events) {
      var max = -1;
      for (var i = 0; i < events.length; i++) {
        var e = events[i];
        if (e.event === "START" && e.n_nodes != null) return e.n_nodes;
        [e.process_id, pidOf(e.process), pidOf(e.peer), pidOf(e.from)].forEach(function (p) {
          if (p != null && p > max) max = p;
        });
      }
      return max < 7 ? 7 : max + 1;
    } };
})(typeof globalThis !== "undefined" ? globalThis : this);
