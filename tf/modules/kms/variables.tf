variable "root_arn" {}

variable "alias" {}

variable "admin_arns" {
  type = "list"
}

variable "user_arns" {
  type = "list"
}

variable "attachment_arns" {
  type = "list"
}
