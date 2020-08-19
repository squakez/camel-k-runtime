/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.k.loader.kotlin

import org.apache.camel.Predicate
import org.apache.camel.Processor
import org.apache.camel.RuntimeCamelException
import org.apache.camel.component.jackson.JacksonDataFormat
import org.apache.camel.component.log.LogComponent
import org.apache.camel.component.seda.SedaComponent
import org.apache.camel.k.loader.kotlin.support.TestRuntime
import org.apache.camel.language.bean.BeanLanguage
import org.apache.camel.model.ProcessDefinition
import org.apache.camel.model.ToDefinition
import org.apache.camel.model.rest.GetVerbDefinition
import org.apache.camel.model.rest.PostVerbDefinition
import org.apache.camel.processor.FatalFallbackErrorHandler
import org.apache.camel.support.DefaultHeaderFilterStrategy
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.sql.DataSource

class KotlinSourceLoaderTest {

    @Test
    fun `load routes`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes.kts")

        val routes = runtime.context.routeDefinitions
        assertThat(routes).hasSize(1)
        assertThat(routes[0].input.endpointUri).isEqualTo("timer:tick")
        assertThat(routes[0].outputs[0]).isInstanceOf(ProcessDefinition::class.java)
        assertThat(routes[0].outputs[1]).isInstanceOf(ToDefinition::class.java)
    }

    @Test
    fun `load routes with endpoint dsl`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-endpoint-dsl.kts")

        val routes = runtime.context.routeDefinitions
        assertThat(routes).hasSize(1)
        assertThat(routes[0].input.endpointUri).isEqualTo("timer://tick?period=1s")
        assertThat(routes[0].outputs[0]).isInstanceOfSatisfying(ToDefinition::class.java) {
            assertThat(it.endpointUri).isEqualTo("log://info")
        }
    }


    @Test
    fun `load integration with rest`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-rest.kts")

        assertThat(runtime.context.restConfiguration.host).isEqualTo("my-host")
        assertThat(runtime.context.restConfiguration.port).isEqualTo(9192)
        assertThat(runtime.context.restDefinitions.size).isEqualTo(2)

        with(runtime.context.restDefinitions.find { it.path == "/my/path" }) {
            assertThat(this?.verbs).hasSize(1)

            with(this?.verbs?.get(0) as GetVerbDefinition) {
                assertThat(uri).isEqualTo("/get")
                assertThat(consumes).isEqualTo("application/json")
                assertThat(produces).isEqualTo("application/json")
                assertThat(to).hasFieldOrPropertyWithValue("endpointUri", "direct:get")
            }
        }

        with(runtime.context.restDefinitions.find { it.path == "/post" }) {
            assertThat(this?.verbs).hasSize(1)

            with(this?.verbs?.get(0) as PostVerbDefinition) {
                assertThat(uri).isNull()
                assertThat(consumes).isEqualTo("application/json")
                assertThat(produces).isEqualTo("application/json")
                assertThat(to).hasFieldOrPropertyWithValue("endpointUri", "direct:post")
            }
        }
    }

    @Test
    fun `load integration with beans`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-beans.kts")

        assertThat(runtime.context.registry.findByType(DataSource::class.java)).hasSize(1)
        assertThat(runtime.context.registry.lookupByName("dataSource")).isInstanceOf(DataSource::class.java)
        assertThat(runtime.context.registry.findByType(DefaultHeaderFilterStrategy::class.java)).hasSize(1)
        assertThat(runtime.context.registry.lookupByName("filterStrategy")).isInstanceOf(DefaultHeaderFilterStrategy::class.java)
        assertThat(runtime.context.registry.lookupByName("myProcessor")).isInstanceOf(Processor::class.java)
        assertThat(runtime.context.registry.lookupByName("myPredicate")).isInstanceOf(Predicate::class.java)
    }

    @Test
    fun `load integration with components configuration`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-components-configuration.kts")

        val seda = runtime.context.getComponent("seda", SedaComponent::class.java)
        val mySeda = runtime.context.getComponent("mySeda", SedaComponent::class.java)
        val log = runtime.context.getComponent("log", LogComponent::class.java)

        assertThat(seda.queueSize).isEqualTo(1234)
        assertThat(seda.concurrentConsumers).isEqualTo(12)
        assertThat(mySeda.queueSize).isEqualTo(4321)
        assertThat(mySeda.concurrentConsumers).isEqualTo(21)
        assertThat(log.exchangeFormatter).isNotNull
    }

    @Test
    fun `load integration with components configuration error`() {
        Assertions.assertThatExceptionOfType(RuntimeCamelException::class.java)
                .isThrownBy { TestRuntime().loadRoutes("classpath:routes-with-components-configuration-error.kts") }
                .withCauseInstanceOf(IllegalArgumentException::class.java)
                .withMessageContaining("Type mismatch, expected: class org.apache.camel.component.log.LogComponent, got: class org.apache.camel.component.seda.SedaComponent");
    }

    @Test
    fun `load integration with languages configuration`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-languages-configuration.kts")

        val bean = runtime.context.resolveLanguage("bean") as BeanLanguage
        assertThat(bean.beanType).isEqualTo(String::class.java)
        assertThat(bean.method).isEqualTo("toUpperCase")

        val mybean = runtime.context.resolveLanguage("my-bean") as BeanLanguage
        assertThat(mybean.beanType).isEqualTo(String::class.java)
        assertThat(mybean.method).isEqualTo("toLowerCase")
    }

    @Test
    fun `load integration with dataformats configuration`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-dataformats-configuration.kts")

        val jackson = runtime.context.resolveDataFormat("json-jackson") as JacksonDataFormat
        assertThat(jackson.unmarshalType).isEqualTo(Map::class.java)
        assertThat(jackson.isPrettyPrint).isTrue()

        val myjackson = runtime.context.resolveDataFormat("my-jackson") as JacksonDataFormat
        assertThat(myjackson.unmarshalType).isEqualTo(String::class.java)
        assertThat(myjackson.isPrettyPrint).isFalse()
    }

    @Test
    fun `load integration with error handler`() {
        val runtime = TestRuntime()
        runtime.loadRoutes("classpath:routes-with-error-handler.kts")
        runtime.start()

        try {
            assertThat(runtime.context.routes).hasSize(1)
            assertThat(runtime.context.routes[0].getOnException("my-on-exception")).isInstanceOf(FatalFallbackErrorHandler::class.java)
        } finally {
            runtime.context.stop()
        }
    }
}