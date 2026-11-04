output "public_ip" {
  value       = oci_core_instance.k3s.public_ip
  description = "IP da VM. Só SSH (22) está aberto."
}

output "instance_id" {
  value       = oci_core_instance.k3s.id
  description = "OCID para Stop/Start no console ou no OCI CLI."
}

output "availability_domain" {
  value = oci_core_instance.k3s.availability_domain
}

output "ssh" {
  value = "ssh ubuntu@${oci_core_instance.k3s.public_ip}"
}

output "next" {
  value = "OCI_HOST=${oci_core_instance.k3s.public_ip} scripts/oci-kubeconfig.sh && OCI_HOST=${oci_core_instance.k3s.public_ip} scripts/oci-load-image.sh"
}
