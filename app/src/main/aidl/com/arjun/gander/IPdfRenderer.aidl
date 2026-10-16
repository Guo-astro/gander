package com.arjun.gander;

/**
 * The PDF renderer for an engine pdf.js can't run on, served by PdfRenderService from an
 * isolated process. Gander hands it a file descriptor and gets bytes back, and nothing else
 * crosses: every call is oneway and answers through a pipe Gander made, as a count of bytes
 * Gander knows in advance, so Gander never reads a reply the renderer wrote. A call to a renderer
 * Android has frozen waits for it to thaw, where one with a reply would fail.
 *
 * Each call closes its pipe when it is done. On any failure the pipe is closed early, so a reader
 * counting the bytes it expects can tell.
 */
oneway interface IPdfRenderer {
    /**
     * Opens the document, closing any open before it, and answers four bytes, little-endian: the
     * page count, or one of the negative codes in PdfRenderService.
     */
    void open(in ParcelFileDescriptor document, in ParcelFileDescriptor answer);

    /**
     * Answers the page's size in points as eight bytes, little-endian: width in the high 32 bits
     * and height in the low, or -1.
     */
    void pageSize(int page, in ParcelFileDescriptor answer);

    /**
     * Draws the page on white at width x height and answers it a row at a time, top row first,
     * four bytes a pixel in the order blue, green, red, alpha.
     */
    void render(int page, int width, int height, in ParcelFileDescriptor answer);
}
