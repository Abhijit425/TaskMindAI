package com.abhijit.airealityscanner.domain.model

/** The intelligent scan modes. Each mode drives its own prompt and result sections. */
enum class ScanMode(
    val id: String,
    val title: String,
    val tagline: String,
    val colorHex: String,
) {
    GENERAL("general", "Object", "Objects, materials and how things work", "#4FD8EB"),
    PLANT("plant", "Plant", "Identify plants and their care needs", "#5BD68A"),
    DOCUMENT("document", "Document", "OCR for bills, receipts, forms and cards", "#7AA7FF"),
    PRODUCT("product", "Product", "Products, packaging and visible specs", "#FFB74D"),
    MACHINE("machine", "Machine", "Machines, electronics and components", "#FF8A65"),
    LANDMARK("landmark", "Landmark", "Buildings, history and architecture", "#BA9CFF"),
    MEDICINE("medicine", "Medicine", "Read medicine labels (no medical advice)", "#FF6F91"),
    ANIMAL("animal", "Animal", "Identify animals and their behaviour", "#C6E377"),
    CURRENCY("currency", "Currency", "Recognise common currency notes", "#FFD54F");

    /** Modes where on-device OCR text is collected first and passed to the AI as a hint. */
    val usesOcr: Boolean
        get() = this == DOCUMENT || this == MEDICINE || this == PRODUCT || this == CURRENCY

    companion object {
        fun fromId(id: String?): ScanMode = entries.firstOrNull { it.id == id } ?: GENERAL
    }
}
