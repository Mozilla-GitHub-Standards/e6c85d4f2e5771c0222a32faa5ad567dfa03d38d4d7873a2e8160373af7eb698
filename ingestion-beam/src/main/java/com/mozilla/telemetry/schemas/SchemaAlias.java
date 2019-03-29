/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.telemetry.schemas;

import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.builder.ToStringBuilder;

class SchemaAlias {

  private final String base;
  private final String alias;

  @JsonCreator
  SchemaAlias(@JsonProperty(value = "base", required = true) String base,
      @JsonProperty(value = "alias", required = true) String alias) {
    this.base = base;
    this.alias = alias;
  }

  String getBase() {
    return base;
  }

  String getAlias() {
    return alias;
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, SHORT_PREFIX_STYLE);
  }
}
