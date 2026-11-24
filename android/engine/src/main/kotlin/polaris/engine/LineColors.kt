package polaris.engine

/**
 * TfL's published line colours, applied to every source's legs so the same
 * line looks the same whichever app found it. Ported from Polaris/Providers/LineColors.swift.
 */
object LineColors {
    val overgroundLines: Set<String> = setOf("liberty", "lioness", "mildmay", "suffragette", "weaver", "windrush")

    private val byLine = mapOf(
        "bakerloo" to "B36305", "central" to "E32017", "circle" to "FFD300", "district" to "00782A",
        "hammersmithandcity" to "F3A9BB", "hammersmithcity" to "F3A9BB", "jubilee" to "A0A5A9",
        "metropolitan" to "9B0056", "northern" to "2B2B2B", "piccadilly" to "1C3F94", "victoria" to "0098D4",
        "waterlooandcity" to "95CDBA", "waterloocity" to "95CDBA",
        "elizabeth" to "6950A1", "dlr" to "00A4A7",
        "liberty" to "61686B", "lioness" to "FFA32B", "mildmay" to "2E86F0", "suffragette" to "59C274",
        "weaver" to "A0457A", "windrush" to "EF4D5E",
        "tram" to "5FB526", "londontrams" to "5FB526",
    )

    private val byMode = mapOf(
        LegMode.bus to "DC241F", LegMode.coach to "B5651D", LegMode.tube to "1C3F94", LegMode.rail to "8A94A6",
        LegMode.overground to "EE7C0E", LegMode.elizabeth to "6950A1", LegMode.dlr to "00A4A7",
        LegMode.tram to "5FB526", LegMode.ferry to "0AA2DC", LegMode.cableCar to "C4122F", LegMode.other to "8A94A6",
    )

    /** Normalised names of the lines with their own colours, for telling which line a status message is about. */
    val namedLines: List<String> get() = byLine.keys.toList()

    fun color(line: String?, mode: LegMode, providerHex: String? = null): RGB? {
        if (line != null) byLine[Journey.normalise(line)]?.let { return RGB.hex(it) }
        RGB.hex(providerHex)?.let { return it }
        return RGB.hex(byMode[mode])
    }
}
