package com.mingeek.studiopop.data.editor

import android.graphics.Matrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import com.mingeek.studiopop.data.effects.builtins.CameraTransform
import com.mingeek.studiopop.data.keyframe.KeyframeTrack

/**
 * R6: 시간 가변 [MatrixTransformation] — Ken Burns / Zoom Punch 같은 카메라 무브를 Media3 export
 * 파이프라인에서 적용한다.
 *
 * 입력은 출력 시각(output ms) 단위 [KeyframeTrack] — 호출 측이 [com.mingeek.studiopop.data.editor.Timeline.rangeToOutputWindows]
 * 로 source→output 매핑을 끝낸 뒤 출력 시각 기반 트랙을 빌드해 넘긴다 (한 source 범위가 여러
 * effective 세그먼트에 걸치면 각 윈도우마다 별도 effect 인스턴스).
 *
 * [outStartMs, outEndMs] 범위 밖의 프레임엔 항등 행렬을 반환 — Media3 effect 체인에 안전히 합류.
 *
 * 행렬 계산:
 *  - p_screen = M * p_source
 *  - 화면 중앙 (0,0) 이 source 의 (cx, cy) 를 비추도록 → t = -s * (cx, cy)
 *  - M = postScale(s, s) followed by postTranslate(-s*cx, -s*cy)
 */
@UnstableApi
class CameraMatrixEffect(
    private val track: KeyframeTrack<CameraTransform>,
    private val outStartMs: Long,
    private val outEndMs: Long,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val timeMs = presentationTimeUs / 1000L
        if (timeMs < outStartMs || timeMs > outEndMs) return IDENTITY
        val tr = track.sampleAt(timeMs) ?: return IDENTITY
        return Matrix().apply {
            postScale(tr.scale, tr.scale)
            postTranslate(-tr.scale * tr.center.x, -tr.scale * tr.center.y)
        }
    }

    companion object {
        private val IDENTITY = Matrix()
    }
}
