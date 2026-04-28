package com.mingeek.studiopop.data.design

import java.util.concurrent.ConcurrentHashMap

/**
 * 전 기능 공유 디자인 자산 단일 소스. 자막·썸네일·숏츠 보더가 모두 이 한 곳을 조회한다.
 *
 * R1 골격 단계에선 시스템 기본만 등록. 실제 폰트/LUT/SFX/BGM/스티커 자산은 사용자 라이센스
 * 결정 후 R2~R3 에서 [registerXxx] 로 채워 넣는다.
 *
 * Thread-safe.
 */
class DesignTokens {

    private val fontPacks = ConcurrentHashMap<String, FontPack>()
    private val palettes = ConcurrentHashMap<String, ColorPalette>()
    private val luts = ConcurrentHashMap<String, LutAsset>()
    private val sfx = ConcurrentHashMap<String, SfxAsset>()
    private val bgm = ConcurrentHashMap<String, BgmAsset>()
    private val stickers = ConcurrentHashMap<String, StickerPack>()
    private val themes = ConcurrentHashMap<String, ThemePack>()

    init {
        // R1: 안전한 기본만 미리 등록.
        registerFontPack(SystemDefaultFontPack)
        registerPalette(StudioPopDefaultPalette)
        registerTheme(StudioPopDefaultTheme)
    }

    // --- registration ----------------------------------------------------

    fun registerFontPack(pack: FontPack) { fontPacks[pack.id] = pack }
    fun registerPalette(palette: ColorPalette) { palettes[palette.id] = palette }
    fun registerLut(lut: LutAsset) { luts[lut.id] = lut }
    fun registerSfx(asset: SfxAsset) { sfx[asset.id] = asset }
    fun registerBgm(asset: BgmAsset) { bgm[asset.id] = asset }
    fun registerStickerPack(pack: StickerPack) { stickers[pack.id] = pack }
    fun registerTheme(theme: ThemePack) { themes[theme.id] = theme }

    // --- lookup ---------------------------------------------------------

    fun fontPack(id: String): FontPack = fontPacks[id] ?: SystemDefaultFontPack
    fun palette(id: String): ColorPalette = palettes[id] ?: StudioPopDefaultPalette
    fun lut(id: String): LutAsset? = luts[id]
    fun sfx(id: String): SfxAsset? = sfx[id]
    fun bgm(id: String): BgmAsset? = bgm[id]
    fun stickerPack(id: String): StickerPack? = stickers[id]
    fun theme(id: String): ThemePack = themes[id] ?: StudioPopDefaultTheme

    // --- listing -------------------------------------------------------

    fun allFontPacks(): List<FontPack> = fontPacks.values.sortedBy { it.id }
    fun allPalettes(): List<ColorPalette> = palettes.values.sortedBy { it.id }
    fun allLuts(): List<LutAsset> = luts.values.sortedBy { it.id }
    fun allSfx(): List<SfxAsset> = sfx.values.sortedBy { it.id }
    fun allBgm(): List<BgmAsset> = bgm.values.sortedBy { it.id }
    fun allStickerPacks(): List<StickerPack> = stickers.values.sortedBy { it.id }
    fun allThemes(): List<ThemePack> = themes.values.sortedBy { it.id }
}
