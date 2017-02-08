variable "availability_zones" {
  type = "list"
}

variable "environment" {}

variable "ami" {}

variable "key_name" {}

variable "instance_count" {
  default = "2"
}

variable "instance_type" {}

variable "security_group_id" {}
variable "iam_instance_profile" {}

variable "target_group_arns" {
  type = "list"
  default = []
}

variable "user_data" {
  default = ""
}
