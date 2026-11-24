package com.andychen.magpie_android.ui

import android.icu.text.CompactDecimalFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAccessor
import java.util.Currency
import java.util.Locale

@Composable
internal fun rememberUsageFormats(): UsageFormats {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { UsageFormats(locale) }
}

internal class UsageFormats(locale: Locale) {
    private val integers = NumberFormat.getIntegerInstance(locale)
    private val compactNumbers = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        .apply { maximumFractionDigits = 1 }
    private val dollars = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = 10
    }
    private val percentages = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }
    private val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
    private val dateTimes = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
        .withLocale(locale)

    fun count(value: Number): String = integers.format(value)
    fun shortCount(value: Int): String = compactNumbers.format(value.toLong())
    fun usd(value: Double): String = dollars.format(value)
    fun percent(value: Double): String = percentages.format(value)
    fun date(value: TemporalAccessor): String = dates.format(value)
    fun dateTime(value: TemporalAccessor): String = dateTimes.format(value)
}
