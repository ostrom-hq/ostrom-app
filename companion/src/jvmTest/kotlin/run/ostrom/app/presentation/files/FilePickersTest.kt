// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation.files

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import run.ostrom.app.domain.models.PickedFile
import java.awt.Image
import java.awt.color.ColorSpace
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.ImageObserver
import java.awt.image.ImageProducer
import java.awt.image.Raster
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePickersTest {
    /** A clipboard or drop holding [data] by flavor; a flavor asked and not held is refused. */
    private class Held(private val data: Map<DataFlavor, Any?>, private val failing: Boolean = false) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
            if (failing) throw IllegalStateException("clipboard busy")
            return flavor in data
        }
        override fun getTransferData(flavor: DataFlavor): Any? = data[flavor]
    }

    private fun PickedFile.bytes(): ByteArray = open().buffered().readByteArray()

    private fun folder(): Path = Files.createTempDirectory("ostrom-pick")

    @Test
    fun onlyRegularFilesAreKeptAndReadWhenSent() {
        val dir = folder()
        val file = dir.resolve("notes.txt").also { Files.writeString(it, "hello") }.toFile()
        val sub = Files.createDirectory(dir.resolve("sub")).toFile()
        val link = Files.createSymbolicLink(dir.resolve("link.txt"), file.toPath()).toFile()
        val picked = desktopFiles(listOf(file, sub, link))
        assertEquals(listOf("notes.txt"), picked.map { it.name })
        assertEquals(5L, picked.single().size)
        // Read from disk each time it is sent: a retry reads it again.
        Files.writeString(file.toPath(), "hello again")
        assertEquals("hello again", picked.single().bytes().decodeToString())
    }

    @Test
    fun aDropOfFilesIsTheirListAndAnythingElseIsNothing() {
        val files = listOf(File("a.txt"), File("b.txt"))
        assertEquals(files, droppedFileList(Held(mapOf(DataFlavor.javaFileListFlavor to files))))
        // A list of something else than files keeps only the files.
        assertEquals(listOf(files[0]), droppedFileList(Held(mapOf(DataFlavor.javaFileListFlavor to listOf(files[0], "text")))))
        assertNull(droppedFileList(Held(mapOf(DataFlavor.javaFileListFlavor to "not a list"))))
        assertNull(droppedFileList(Held(mapOf(DataFlavor.stringFlavor to "text"))))
        assertNull(droppedFileList(Held(emptyMap(), failing = true)))
    }

    @Test
    fun aPasteAttachesFilesOrAnImageAloneNeverAnImageWithText() {
        assertTrue(pastesFiles(Held(mapOf(DataFlavor.javaFileListFlavor to emptyList<File>()))))
        assertTrue(pastesFiles(Held(mapOf(DataFlavor.imageFlavor to BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)))))
        assertFalse(pastesFiles(Held(mapOf(DataFlavor.imageFlavor to BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), DataFlavor.stringFlavor to "cells"))))
        assertFalse(pastesFiles(Held(mapOf(DataFlavor.stringFlavor to "text"))))
        assertFalse(pastesFiles(Held(emptyMap(), failing = true)))
        assertNull(pastedFiles(Held(mapOf(DataFlavor.stringFlavor to "text"))))
    }

    @Test
    fun copiedFilesGoThroughTheRegularFileRule() {
        val dir = folder()
        val file = dir.resolve("a.bin").also { Files.write(it, byteArrayOf(1, 2, 3)) }.toFile()
        val folder = Files.createDirectory(dir.resolve("sub")).toFile()
        val pasted = pastedFiles(Held(mapOf(DataFlavor.javaFileListFlavor to listOf(file, folder))))!!
        assertEquals(listOf("a.bin"), pasted.map { it.name })
        // A clipboard that holds no list of files attaches nothing.
        assertEquals(emptyList(), pastedFiles(Held(mapOf(DataFlavor.javaFileListFlavor to "odd")))!!.map { it.name })
    }

    @Test
    fun aCopiedImageBecomesAPngNamedAfterTheTime() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        val pasted = pastedFiles(Held(mapOf(DataFlavor.imageFlavor to image)), now = { 42L })!!.single()
        assertEquals("pasted-image-42.png", pasted.name)
        assertEquals("image/png", pasted.type)
        val bytes = pasted.bytes()
        assertEquals(bytes.size.toLong(), pasted.size)
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
        // Sent again, it is the same picture.
        assertContentEquals(bytes, pasted.bytes())
    }

    @Test
    fun anImageThatIsNotABufferedOneIsDrawnFirst() {
        val source = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val scaled = source.getScaledInstance(4, 4, Image.SCALE_DEFAULT)
        val pasted = pastedFiles(Held(mapOf(DataFlavor.imageFlavor to scaled)), now = { 7L })!!.single()
        assertEquals("pasted-image-7.png", pasted.name)
        assertNotNull(pasted.bytes())
    }

    private class Sized(private val w: Int, private val h: Int) : Image() {
        override fun getWidth(observer: ImageObserver?) = w
        override fun getHeight(observer: ImageObserver?) = h
        override fun getSource(): ImageProducer? = null
        override fun getGraphics(): java.awt.Graphics? = null
        override fun getProperty(name: String?, observer: ImageObserver?): Any? = null
    }

    @Test
    fun anImageWithoutSizeOrThatIsNotAnImageAttachesNothing() {
        assertEquals(emptyList(), pastedFiles(Held(mapOf(DataFlavor.imageFlavor to Sized(0, 4))))!!)
        assertEquals(emptyList(), pastedFiles(Held(mapOf(DataFlavor.imageFlavor to Sized(4, 0))))!!)
        assertEquals(emptyList(), pastedFiles(Held(mapOf(DataFlavor.imageFlavor to "not an image")))!!)
    }

    /** Five bands in one pixel: more than a PNG holds. */
    private class FiveBands : ColorSpace(TYPE_5CLR, 5) {
        override fun toRGB(colorvalue: FloatArray) = floatArrayOf(0f, 0f, 0f)
        override fun fromRGB(rgbvalue: FloatArray) = FloatArray(5)
        override fun toCIEXYZ(colorvalue: FloatArray) = floatArrayOf(0f, 0f, 0f)
        override fun fromCIEXYZ(colorvalue: FloatArray) = FloatArray(5)
    }

    @Test
    fun aPictureThatCannotBeWrittenAsPngAttachesNothing() {
        val model = ComponentColorModel(FiveBands(), false, false, java.awt.Transparency.OPAQUE, DataBuffer.TYPE_BYTE)
        val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 2, 2, 5, null)
        val image = BufferedImage(model, raster, false, null)
        assertEquals(emptyList(), pastedFiles(Held(mapOf(DataFlavor.imageFlavor to image)))!!)
    }
}
