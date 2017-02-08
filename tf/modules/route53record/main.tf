resource "aws_route53_record" "default" {
  zone_id = "${var.zone_id}"
  name = "${var.name}"
  type = "${var.type}"

  alias {
    # https://github.com/hashicorp/terraform/issues/10869 for why we do lower
    name = "${lower(var.target_dns_name)}"
    zone_id = "${var.target_zone_id}"
    evaluate_target_health = false
  }
}
