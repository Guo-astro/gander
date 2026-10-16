package com.arjun.gander;

/**
 * The PDF renderer for an engine pdf.js can't run on, served by PdfRenderService from an
 * isolated process. Gander hands it a file descriptor and gets pixels back, and nothing else
 * crosses.
 */
interface IPdfRenderer {
    /**
     * Opens the document, closing any open before it. The page count, or one of the negative
     * codes in PdfRenderService.
     */
    int open(in ParcelFileDescriptor document);

    /** The page's size in points, width in the high 32 bits and height in the low, or -1. */
    long pageSize(int page);

    /**
     * Draws the page on white at width x height and writes it to sink a row at a time, top row
     * first, four bytes a pixel in the order blue, green, red, alpha, then closes sink. On any
     * failure sink is closed early, so a reader counting the bytes it expects can tell.
     */
    oneway void render(int page, int width, int height, in ParcelFileDescriptor sink);
}
