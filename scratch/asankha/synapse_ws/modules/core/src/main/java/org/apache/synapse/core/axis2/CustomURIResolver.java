/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.core.axis2;

import org.apache.synapse.config.SynapseConfigUtils;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.ws.commons.schema.resolver.URIResolver;
import org.xml.sax.InputSource;

/**
 * Class that adapts a ResourceMap to URIResolver.
 */
public class CustomURIResolver implements URIResolver {
    private ResourceMap resourceMap;
    private SynapseConfiguration synCfg;

    public CustomURIResolver() {
    }

    public CustomURIResolver(ResourceMap resourceMap,
                                  SynapseConfiguration synCfg) {
        this();
        this.resourceMap = resourceMap;
        this.synCfg = synCfg;
    }

    public InputSource resolveEntity(String targetNamespace, String schemaLocation, String baseUri) {
        InputSource result = null;
        if (resourceMap != null) {
            result = resourceMap.resolve(synCfg, schemaLocation);
        }
        if (result == null) {
            result = SynapseConfigUtils.resolveRelativeURI(baseUri, schemaLocation);
        }
        return result;
    }
}
