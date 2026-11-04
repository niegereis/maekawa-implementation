terraform {
  required_version = ">= 1.5.0"
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 6.36.0"
    }
  }
}

provider "oci" {
  config_file_profile = var.config_file_profile
  region              = var.region
}

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.compartment_ocid
}

data "oci_core_images" "ubuntu" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = var.ubuntu_version
  shape                    = var.shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
  state                    = "AVAILABLE"
}

locals {
  ad       = data.oci_identity_availability_domains.ads.availability_domains[var.ad_index].name
  image_id = data.oci_core_images.ubuntu.images[0].id
}

resource "oci_core_vcn" "this" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.0.0.0/16"]
  display_name   = "${var.display_name}-vcn"
  dns_label      = "maekawa"
}

resource "oci_core_internet_gateway" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.display_name}-igw"
  enabled        = true
}

resource "oci_core_route_table" "this" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.display_name}-rt"
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this.id
  }
}

# Só SSH. 6443, 80, 443 e as portas do protocolo ficam fechadas.
resource "oci_core_network_security_group" "ssh" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.display_name}-ssh"
}

resource "oci_core_network_security_group_security_rule" "ssh_in" {
  network_security_group_id = oci_core_network_security_group.ssh.id
  direction                 = "INGRESS"
  protocol                  = "6"
  source                    = var.ssh_ingress_cidr
  source_type               = "CIDR_BLOCK"
  tcp_options {
    destination_port_range {
      min = 22
      max = 22
    }
  }
}

resource "oci_core_network_security_group_security_rule" "egress" {
  network_security_group_id = oci_core_network_security_group.ssh.id
  direction                 = "EGRESS"
  protocol                  = "all"
  destination               = "0.0.0.0/0"
  destination_type          = "CIDR_BLOCK"
}

resource "oci_core_security_list" "ssh" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.display_name}-sl"
  ingress_security_rules {
    protocol    = "6"
    source      = var.ssh_ingress_cidr
    source_type = "CIDR_BLOCK"
    tcp_options {
      min = 22
      max = 22
    }
  }
  egress_security_rules {
    protocol         = "all"
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
  }
}

resource "oci_core_subnet" "public" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.this.id
  cidr_block                 = "10.0.0.0/24"
  display_name               = "${var.display_name}-public"
  dns_label                  = "public"
  route_table_id             = oci_core_route_table.this.id
  security_list_ids          = [oci_core_security_list.ssh.id]
  prohibit_public_ip_on_vnic = false
}

resource "oci_core_instance" "k3s" {
  compartment_id      = var.compartment_ocid
  availability_domain = local.ad
  display_name        = var.display_name
  shape               = var.shape

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  source_details {
    source_type             = "image"
    source_id               = local.image_id
    boot_volume_size_in_gbs = var.boot_volume_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.public.id
    assign_public_ip = true
    nsg_ids          = [oci_core_network_security_group.ssh.id]
    hostname_label   = "k3s"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }

  freeform_tags = {
    project = "bcc362-tp2"
    role    = "k3s"
  }

  lifecycle {
    precondition {
      condition     = var.ocpus <= 2 && var.memory_in_gbs <= 12
      error_message = "Always Free Ampere A1 hoje é 2 OCPU e 12 GiB no total. Acima disso cobra se a conta for PAYG."
    }
  }
}
