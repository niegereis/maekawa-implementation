/* Estado do plano de Fano a partir dos JSON Lines (LOCKED, RELEASE, BEGIN, END)
   e do modo do experimento (MODE_INIT, MODE_CHANGE, MODE_SETTLED do observador). */
(function (root) {
  "use strict";

  var SEND = { REQUEST: 1, LOCKED: 1, FAILED: 1, INQUIRE: 1, RELINQUISH: 1, RELEASE: 1 };
  var OBS = { BEGIN: 1, END: 1, VIOLATION: 1, MODE_INIT: 1, MODE_CHANGE: 1, MODE_SETTLED: 1, CS_CHANGE: 1, COUNTERS_RESET: 1 };

  function pidOf(name) {
    if (typeof name === "number" && name >= 0 && name <= 6) return name;
    var m = /^sync-(\d)$/.exec(String(name == null ? "" : name));
    return m ? Number(m[1]) : null;
  }

  function keep(e) {
    if (!e || typeof e !== "object") return false;
    var ev = e.event;
    if (e.direction === "send" && SEND[ev]) return true;
    if (e.process === "observer" && OBS[ev]) return true;
    return false;
  }

  function initialState() {
    return {
      vote: [null, null, null, null, null, null, null],
      inR: [false, false, false, false, false, false, false],
      requesting: [false, false, false, false, false, false, false],
      held: [null, null, null, null, null, null, null],
      violations: 0,
      transitionViolations: 0,
      overlap: false,
      sync: null,      // null = log sem eventos de modo (rodada antiga)
      epoch: null,
      settled: true,
      cs: null         // [min, max] em ms, depois de um CS_CHANGE
    };
  }

  function apply(state, e) {
    var ev = e.event;
    var self = e.process_id != null ? e.process_id : pidOf(e.process);
    var peer = pidOf(e.peer);
    if (e.direction === "send" && SEND[ev]) {
      if (ev === "REQUEST" && self != null) state.requesting[self] = true;
      if (ev === "LOCKED" && self != null && peer != null) state.vote[self] = peer;
      if ((ev === "RELEASE" || ev === "RELINQUISH") && peer != null) state.vote[peer] = null;
    } else if (e.process === "observer" && ev === "BEGIN") {
      var p = pidOf(e.from);
      if (p != null) {
        state.inR[p] = true;
        state.requesting[p] = false;
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
    } else if (ev === "CS_CHANGE") {
      state.cs = [e.cs_min_ms, e.cs_max_ms];
    }
    return state;
  }

  function at(events, index) {
    var s = initialState();
    var last = Math.min(index, events.length - 1);
    for (var i = 0; i <= last; i++) apply(s, events[i]);
    return s;
  }

  root.MaekawaReplay = { pidOf: pidOf, keep: keep, initialState: initialState, apply: apply, at: at };
})(typeof globalThis !== "undefined" ? globalThis : this);
