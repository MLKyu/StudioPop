package com.mingeek.studiopop.data.effects

/**
 * 효과의 큰 분류. UI 의 효과 패널이 카테고리별 탭/그룹으로 보여줌.
 * 새 카테고리는 자유롭게 추가할 수 있음.
 */
enum class EffectCategory(val label: String) {
    /** 자막·텍스트 스타일 (Glow, Neon, 3D, Gradient 등). */
    CAPTION_STYLE("자막 스타일"),

    /** 텍스트 진입/퇴장 애니메이션. */
    TEXT_ANIMATION("텍스트 애니"),

    /** 세그먼트 사이 전환 (Fade, Wipe, Glitch, Zoom Punch). */
    TRANSITION("전환"),

    /** 컬러 룩업 (Cinematic, Vivid, Mono, Vintage). */
    LUT("컬러 룩"),

    /** 영상 위 PNG/MP4 오버레이 (Light leak, dust, grain). */
    OVERLAY("오버레이"),

    /** 정지 이미지·움직이는 스티커. */
    STICKER("스티커"),

    /** 영상 변형 (Ken Burns, Zoom Punch, Speed Ramp). */
    VIDEO_FX("영상 효과"),

    /** 오디오 처리 (Ducking, EQ 프리셋). */
    AUDIO_FX("오디오 효과"),

    /** 썸네일 데코 (Box, Outline, Arrow, Glow, Highlight). */
    THUMBNAIL_DECO("썸네일 데코"),

    /** 숏츠 전용 패키지 (인트로/아웃트로/구독 카드). */
    SHORTS_PIECE("숏츠 조각"),
}
