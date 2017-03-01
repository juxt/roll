variable "listen_port" {
  default = 80
}

variable "protocol" {
  default = "HTTP"
}

variable "ssl_policy" {
  default = ""
}

variable "certificate_arn" {
  default = ""
}

variable "target_group_arn" {
  default = ""
}

variable "load_balancer_arn" {
  default = ""
}
