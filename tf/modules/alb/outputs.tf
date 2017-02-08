output "arn" {
  value = "${aws_alb_target_group.website.arn}"
}

output "zone_id" {
  value = "${aws_alb.main.zone_id}"
}

output "dns_name" {
  value = "${aws_alb.main.dns_name}"
}
