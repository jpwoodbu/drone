package one.woodbury.drone.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import one.woodbury.drone.flight.FlightDynamicsCalculator
import one.woodbury.drone.protocol.BatteryTelemetry

/**
 * Modal dialog displaying detailed Intelligent Flight Battery health, electrical metrics, and cell voltages.
 */
@Composable
fun BatteryHealthDialog(
    batteryTelemetry: BatteryTelemetry?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Battery Health & Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            if (batteryTelemetry == null) {
                Text(
                    text = "No battery telemetry received from aircraft.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val percentageText = batteryTelemetry.percentage?.let { "$it%" } ?: "—"
                    val powerText = FlightDynamicsCalculator.formatPowerWatts(batteryTelemetry.powerWatts)
                    MetricRow(label = "State of Charge", value = "$percentageText ($powerText)")

                    val voltageText = batteryTelemetry.packVoltageMillivolts?.let { millivolts ->
                        val volts = millivolts / 1000.0
                        if (batteryTelemetry.cellCount != null) {
                            "%.2f V (%dS)".format(volts, batteryTelemetry.cellCount)
                        } else {
                            "%.2f V".format(volts)
                        }
                    } ?: "—"
                    MetricRow(label = "Pack Voltage", value = voltageText)

                    val currentText = batteryTelemetry.currentMilliamps?.let { milliamps ->
                        val amperes = milliamps / 1000.0
                        val direction = if (milliamps < 0) "Discharge" else if (milliamps > 0) "Charge" else "Idle"
                        "%.2f A (%s)".format(amperes, direction)
                    } ?: "—"
                    MetricRow(label = "Current Draw", value = currentText)

                    val flightTimeText = FlightDynamicsCalculator.formatEstimatedFlightTime(
                        batteryTelemetry.estimatedFlightTimeMinutes,
                    )
                    MetricRow(label = "Estimated Flight Time", value = flightTimeText)

                    val tempText = batteryTelemetry.temperatureCelsius?.let { "%.1f °C".format(it) } ?: "—"
                    MetricRow(label = "Battery Temperature", value = tempText)

                    val capacityText = if (batteryTelemetry.remainingCapacityMilliampHours != null &&
                        batteryTelemetry.fullCapacityMilliampHours != null
                    ) {
                        "%,d / %,d mAh".format(
                            batteryTelemetry.remainingCapacityMilliampHours,
                            batteryTelemetry.fullCapacityMilliampHours,
                        )
                    } else if (batteryTelemetry.remainingCapacityMilliampHours != null) {
                        "%,d mAh".format(batteryTelemetry.remainingCapacityMilliampHours)
                    } else {
                        "—"
                    }
                    MetricRow(label = "Charge Capacity", value = capacityText)

                    val healthText = batteryTelemetry.lifetimeHealthPercentage?.let { "$it%" } ?: "—"
                    MetricRow(label = "Lifetime Health (SOH)", value = healthText)

                    val cyclesText = batteryTelemetry.cycleCount?.let { "$it cycles" } ?: "—"
                    MetricRow(label = "Discharge Cycles", value = cyclesText)

                    if (batteryTelemetry.cellVoltagesMillivolts.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Individual Cell Voltages",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )

                        batteryTelemetry.cellVoltagesMillivolts.forEachIndexed { index, cellMv ->
                            MetricRow(label = "Cell ${index + 1}", value = "$cellMv mV")
                        }

                        val deltaMv = batteryTelemetry.cellDeltaMillivolts
                        if (deltaMv != null) {
                            val isHighDelta = deltaMv > 30
                            MetricRow(
                                label = "Cell Imbalance (Δ)",
                                value = "$deltaMv mV",
                                valueColor = if (isHighDelta) Color(0xFFF85149) else Color(0xFF3FB950),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Close")
            }
        },
    )
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}
