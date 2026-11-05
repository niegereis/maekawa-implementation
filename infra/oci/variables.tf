variable "compartment_ocid" {
  type        = string
  description = "OCID do compartment (a tenancy serve). Sempre na home region."
}

variable "ssh_public_key" {
  type        = string
  description = "Chave pública SSH (conteúdo de ~/.ssh/id_ed25519.pub)."
}

variable "config_file_profile" {
  type        = string
  default     = "DEFAULT"
  description = "Perfil em ~/.oci/config. Credenciais não entram no repositório."
}

variable "region" {
  type        = string
  description = "Home region da tenancy. Always Free só vale nela."
}

variable "ad_index" {
  type        = number
  default     = 0
  description = "Availability domain (0, 1, 2). Trocar se der out of host capacity."
}

variable "shape" {
  type        = string
  default     = "VM.Standard.A1.Flex"
  description = "Always Free: VM.Standard.A1.Flex. Sem estoque Ampere: VM.Standard.E4.Flex (trial). Não usar E2.1.Micro."
}

variable "ocpus" {
  type        = number
  default     = 2
  description = "Teto Always Free atual: 2 OCPU no total da tenancy."
}

variable "memory_in_gbs" {
  type        = number
  default     = 12
  description = "Teto Always Free atual: 12 GiB no total da tenancy."
}

variable "boot_volume_gbs" {
  type        = number
  default     = 50
  description = "Mínimo Always Free. Os 200 GiB de Block Volume são da tenancy inteira."
}

variable "ubuntu_version" {
  type        = string
  default     = "22.04"
  description = "Imagem Canonical Ubuntu Always Free Eligible, aarch64."
}

variable "ssh_ingress_cidr" {
  type        = string
  default     = "0.0.0.0/0"
  description = "Origem do SSH. Restringir ao IP da casa/UFOP se possível."
}

variable "display_name" {
  type        = string
  default     = "maekawa-k3s"
}
