package com.github.copilotmonitor.services

import com.github.copilotmonitor.CopilotMonitorTopics
import com.github.copilotmonitor.InteractionListener
import com.github.copilotmonitor.model.Interaction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

@Service(Service.Level.APP)
class OtelExportService : AutoCloseable {

    private val logger = thisLogger()
    private val settings get() = com.github.copilotmonitor.settings.CopilotMonitorSettings.getInstance()
    private var sdk: OpenTelemetrySdk? = null
    private var tracer: Tracer? = null

    fun init() {
        if (!settings.otelEnabled) return
        try {
            setupSdk()
            subscribeToInteractions()
            logger.info("OTel export initialized with endpoint ${settings.otelEndpoint}")
        } catch (e: Exception) {
            logger.warn("Failed to initialize OTel export: ${e.message}")
        }
    }

    private fun setupSdk() {
        val tracesEndpoint = if (settings.otelEndpoint.endsWith("/v1/traces"))
            settings.otelEndpoint
        else
            "${settings.otelEndpoint.trimEnd('/')}/v1/traces"

        val exporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(tracesEndpoint)
            .build()

        val resource = Resource.getDefault().merge(
            Resource.create(io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),
                "copilot-monitor"
            ))
        )

        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .setResource(resource)
            .build()

        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()

        tracer = sdk!!.getTracer("com.github.copilotmonitor", "1.0.0")
    }

    private fun subscribeToInteractions() {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(CopilotMonitorTopics.INTERACTION_EVENT, object : InteractionListener {
                override fun onInteraction(interaction: Interaction) {
                    exportInteraction(interaction)
                }
            })
    }

    private fun exportInteraction(interaction: Interaction) {
        val t = tracer ?: return
        try {
            val span = t.spanBuilder("gen_ai.${interaction.featureType.name.lowercase()}")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()

            span.setAttribute("gen_ai.request.model", interaction.model)
            span.setAttribute("gen_ai.provider.name", interaction.provider)
            span.setAttribute("gen_ai.usage.input_tokens", interaction.inputTokens)
            span.setAttribute("gen_ai.usage.output_tokens", interaction.outputTokens)
            span.setAttribute("gen_ai.usage.cache_read.input_tokens", interaction.cacheReadTokens)
            span.setAttribute("gen_ai.usage.cache_creation.input_tokens", interaction.cacheCreationTokens)
            span.setAttribute("gen_ai.response.finish_reasons", interaction.finishReason.name)

            if (interaction.latencyMs >= 0) {
                span.setAttribute("gen_ai.client.operation.duration_ms", interaction.latencyMs)
            }

            span.end()
        } catch (e: Exception) {
            logger.debug("Failed to export OTel span: ${e.message}")
        }
    }

    override fun close() {
        sdk?.close()
    }
}
