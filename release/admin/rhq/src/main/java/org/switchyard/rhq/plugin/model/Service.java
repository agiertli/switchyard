/*
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.switchyard.rhq.plugin.model;

import java.util.Map;

import javax.xml.namespace.QName;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * SwitchYard Service
 */
@JsonIgnoreProperties(ignoreUnknown=true)
public class Service implements NamedResource {
    private final QName name;
    private final String interfaceName;
    private final QName promotedService;
    private final Map<String, Gateway> gateways;
    private final Throttling throttling;
    
    @JsonCreator
    public Service(@JsonProperty("name") QName name,
            @JsonProperty("interface") String interfaceName,
            @JsonProperty("promotedService") QName promotedService,
            @JsonProperty("gateways") Gateway[] gateways,
            @JsonProperty("throttling") Throttling throttling) {
        this.name = name;
        this.interfaceName = interfaceName;
        this.promotedService = promotedService;
        this.gateways = ModelUtil.createNamedResourceMap(gateways);
        this.throttling = throttling;
    }

    public QName getName() {
        return name;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public QName getPromotedService() {
        return promotedService;
    }

    public Map<String, Gateway> getGateways() {
        return gateways;
    }

    public Throttling getThrottling() {
        return throttling;
    }
}
