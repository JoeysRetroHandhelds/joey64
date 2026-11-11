package com.joeysretro.joey64.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlangTest {
    @Test fun pathsResolveInsideThePickedFolder() {
        assertEquals("crt/shaders/a.slang", Slang.resolve("crt", "shaders/a.slang"))
        assertEquals("include/gamma.h", Slang.resolve("crt/shaders/royale", "../../../include/gamma.h"))
        assertEquals("crt/a.slang", Slang.resolve("crt", "./a.slang"))
        assertEquals("crt/x/a.png", Slang.resolve("crt", "x\\a.png"))
        assertNull(Slang.resolve("crt", "../../outside.slang"))
        assertNull(Slang.resolve("", "../a.slang"))
    }

    @Test fun presetNamesItsShadersTexturesAndReferences() {
        val text = """
            #reference "../base/base.slangp"
            shaders = 2
            shader0 = "shaders/crt-geom.slang" # the main pass
            filter_linear0 = false
            shader1 = shaders/blur.slang
            // a comment line
            textures = "mask;LUT"
            mask = "../textures/mask.png"
            LUT = textures/lut.png
            mask_linear = true
        """.trimIndent()
        assertEquals(listOf("../base/base.slangp", "shaders/crt-geom.slang", "shaders/blur.slang", "../textures/mask.png", "textures/lut.png"),
            Slang.presetRefs(text))
    }

    @Test fun shaderIncludes() {
        val text = "#version 450\n#include \"../include/compat.h\"\n  #include \"params.inc\" // its settings\nvoid main() {}"
        assertEquals(listOf("../include/compat.h", "params.inc"), Slang.includes(text))
    }

    @Test fun depsFollowEverythingAndListWhatIsMissing() {
        val files = mapOf(
            "crt/geom.slangp" to "shaders = 1\nshader0 = shaders/geom.slang\ntextures = mask\nmask = mask.png",
            "crt/shaders/geom.slang" to "#include \"../../include/compat.h\"",
            "include/compat.h" to "// nothing",
            "crt/mask.png" to "")
        val d = Slang.deps("crt/geom.slangp", { files[it] })
        assertEquals(listOf("crt/geom.slangp", "crt/shaders/geom.slang", "include/compat.h", "crt/mask.png"), d.files)
        assertTrue(d.missing.isEmpty())

        val broken = files - "include/compat.h"
        val m = Slang.deps("crt/geom.slangp", { broken[it] })
        assertEquals(listOf("crt/shaders/geom.slang: include/compat.h"), m.missing)
    }

    @Test fun includeCyclesStop() {
        val files = mapOf("a.slangp" to "shader0 = a.slang", "a.slang" to "#include \"b.h\"", "b.h" to "#include \"a.slang\"")
        assertEquals(listOf("a.slangp", "a.slang", "b.h"), Slang.deps("a.slangp", { files[it] }).files)
    }

    @Test fun valuesAndNames() {
        val v = "slang:shaders_slang/crt/crt-geom.slangp"
        assertTrue(Slang.isSlang(v))
        assertEquals("crt-geom", Slang.name(v))
        assertEquals("crt", Slang.folder("crt/crt-geom.slangp"))
        assertEquals("", Slang.folder("zfast.slangp"))
    }

    @Test fun paramsRoundTrip() {
        val m = mapOf("CRTgamma" to 2.4f, "cornersize" to 0.03f, "x" to 1f)
        assertEquals("CRTgamma=2.4;cornersize=0.03;x=1", Slang.paramsString(m))
        assertEquals(m, Slang.paramsOf(Slang.paramsString(m)))
        assertEquals(emptyMap<String, Float>(), Slang.paramsOf(""))
        assertEquals(mapOf("a" to 1f), Slang.paramsOf("a=1;b=zz;=3"))
    }

    @Test fun formatFollowsTheStep() {
        // As the approved mockup draws crt-geom's.
        assertEquals("2.40", Slang.format(2.4f, 0.1f))
        assertEquals("1.00", Slang.format(1f, 1f))
        assertEquals("0.030", Slang.format(0.03f, 0.005f))
        assertEquals("1000", Slang.format(1000f, 10f))
        assertEquals("0.05", Slang.format(0.05f, 0.05f))
    }

    @Test fun stepsStayOnTheGridAndInRange() {
        val p = Slang.Param("g", "Gamma", 2.4f, 0.1f, 5f, 0.1f)
        assertEquals(2.5f, p.stepped(2.4f, 1), 1e-4f)
        assertEquals(5f, p.stepped(5f, 1), 1e-4f)
        assertEquals(0.1f, p.stepped(0.1f, -1), 1e-4f)
        assertEquals("Gamma", p.label)
        assertEquals("g", p.copy(description = " ").label)
    }

    @Test fun hostParamsParse() {
        val (ps, err) = Slang.parseParams(arrayOf("a", "A", "1", "0", "2", "0.5", "b", "", "0", "0", "1", "1"))
        assertNull(err)
        assertEquals(2, ps!!.size)
        assertEquals(Slang.Param("a", "A", 1f, 0f, 2f, 0.5f), ps[0])
        val (none, msg) = Slang.parseParams(arrayOf("!", "could not find file"))
        assertNull(none)
        assertEquals("could not find file", msg)
    }

    @Test fun plainErrors() {
        assertTrue(Slang.plainError("could not find file \"../include/gamma.h\"").startsWith("A file it uses is missing"))
        assertTrue(Slang.plainError("not in the picked folder: x").startsWith("A file it uses is missing"))
        assertEquals("librashader couldn't load it.", Slang.plainError("something odd"))
    }

    @Test fun shaderRowAndPresetsKnowSlang() {
        val v = VideoSettings(mapOf(VideoKey.Shader to "slang:s/crt/crt-geom.slangp", VideoKey.SlangParams to "CRTgamma=2.2"))
        val s = Picture.setup(v, { null }, autoSharp = true)
        assertEquals("slang:s/crt/crt-geom.slangp", s.slang)
        assertEquals(mapOf("CRTgamma" to 2.2f), s.slangParams)
        assertTrue(s.stack.isEmpty())
        assertEquals("crt-geom", s.label)
        assertEquals("Your own: crt-geom", Picture.rowLabel(v))
        assertEquals("crt-geom didn't load", Picture.rowLabel(v, setOf("slang:s/crt/crt-geom.slangp")))
        // The host gets no built-in shader with it.
        assertTrue(Picture.native(v, s).ids.all { it == 0 })
        val back = ShaderPreset.fromJson(s.toJson("Geom"))!!
        assertEquals(s, back.setup)
    }
}
