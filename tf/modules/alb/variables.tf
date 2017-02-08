variable "environment" {}

variable "name" {}

variable "vpc_id" {}

variable "subnet_ids" {
  type = "list"
}

variable "listen_port" {
  default = 80
}

variable "protocol" {
  default = "HTTP"
}

variable "forward_port" {
  default = 8080
}

variable "ssl_policy" {
  default = ""
}

variable "certificate_arn" {
  default = ""
}
