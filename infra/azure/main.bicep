// AKS minimo para o TP2: tier Free (control plane sem custo), um node pool de sistema com 1 no.
// Deploy no escopo do resource group:
//   az group create -n rg-bcc362-tp2-maekawa -l <regiao>
//   az deployment group create -g rg-bcc362-tp2-maekawa --template-file infra/azure/main.bicep --parameters infra/azure/parameters.json
targetScope = 'resourceGroup'

@description('Nome do cluster')
param clusterName string = 'aks-maekawa'

@description('Regiao; padrao = a do resource group. Precisa aceitar o SKU do no e o AKS na assinatura de estudante.')
param location string = resourceGroup().location

@description('Tamanho do no. AKS exige >= 2 vCPU e >= 4 GiB; os SKUs gratuitos de 1 GiB nao servem.')
param nodeVmSize string = 'Standard_B2s'

@description('Numero de nos. 1 basta para o TP; 2 so para mostrar pods em hosts distintos.')
@minValue(1)
@maxValue(2)
param nodeCount int = 1

@description('Versao do Kubernetes; vazio = padrao da regiao (az aks get-versions).')
param kubernetesVersion string = ''

resource aks 'Microsoft.ContainerService/managedClusters@2024-02-01' = {
  name: clusterName
  location: location
  sku: {
    name: 'Base'
    tier: 'Free'
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    dnsPrefix: clusterName
    kubernetesVersion: empty(kubernetesVersion) ? null : kubernetesVersion
    enableRBAC: true
    agentPoolProfiles: [
      {
        name: 'system'
        mode: 'System'
        count: nodeCount
        vmSize: nodeVmSize
        osType: 'Linux'
        osSKU: 'AzureLinux'
        osDiskSizeGB: 32
        osDiskType: 'Managed'
        type: 'VirtualMachineScaleSets'
        enableAutoScaling: false
      }
    ]
    networkProfile: {
      networkPlugin: 'azure'
      networkPluginMode: 'overlay'
      networkPolicy: 'none'
      loadBalancerSku: 'standard'
      outboundType: 'loadBalancer'
    }
  }
}

output clusterName string = aks.name
output kubeconfigCommand string = 'az aks get-credentials -g ${resourceGroup().name} -n ${aks.name}'
output stopCommand string = 'az aks stop -g ${resourceGroup().name} -n ${aks.name}'
