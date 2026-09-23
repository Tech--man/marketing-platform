package com.example.marketing.admin.dto;

/** admin_config 的一行：某个形态上的覆盖值。 */
public record ConfigFormValueView(String form, String value, String version,
                                  String updatedBy, String remark) {
}
