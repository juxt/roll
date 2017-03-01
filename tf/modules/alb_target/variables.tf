variable "environment" {}

variable "name" {}

variable "vpc_id" {}

variable "subnet_ids" {
  type = "list"
}

variable "forward_port" {
  default = 8080
}

variable "security_groups" {
  type = "list"
}
