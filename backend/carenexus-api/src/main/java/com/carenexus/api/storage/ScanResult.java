package com.carenexus.api.storage;

/**
 * Result of {@link FileScanner#scan}.
 *
 * @param clean               false when the scanner found something harmful; the file must be rejected
 * @param detectedContentType type detected from the bytes, never the declared one: {@code application/pdf},
 *                            {@code image/jpeg}, {@code image/png}, {@code application/dicom}, or
 *                            {@code application/octet-stream} when the content is none of them. Never null
 * @param reason              short technical reason when {@code clean} is false (not shown to users), else null
 */
public record ScanResult(boolean clean, String detectedContentType, String reason) {

    public static final String PDF = "application/pdf";
    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";
    public static final String DICOM = "application/dicom";
    public static final String UNKNOWN = "application/octet-stream";
}
