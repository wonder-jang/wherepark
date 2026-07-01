package com.wonder.wherepark.analyze;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.wonder.wherepark.data.model.Enums.ParkingLevelType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주차 표지판 사진을 온디바이스로 분석해 주차 정보를 추출한다.
 * <ul>
 *   <li>층: ML Kit OCR 텍스트에서 {@code B2F/B2/3F/지하2/지상3/n층} 형태를 인식</li>
 *   <li>위치: 층 표기를 제외한 {@code A12/A-12/가12} 같은 구역 표기 또는 보조 숫자</li>
 *   <li>배경색: 사진 픽셀의 우세 색상(HSV 투표)을 한국어 색 이름으로 분류</li>
 * </ul>
 *
 * <p>OCR/색상은 모두 휴리스틱 best-effort이므로 결과는 항상 사용자 확인을 거쳐 저장한다.
 * 콜백은 ML Kit 규약상 호출한 스레드(보통 메인 스레드)로 전달된다.
 */
public final class ParkingPhotoAnalyzer {

    /** 분석 결과. 인식 실패 항목은 null이다. */
    public static final class Result {
        /** 지하/지상. 인식 못 하면 null(=기타로 취급). */
        @Nullable
        public ParkingLevelType levelType;
        /** 층 번호(1~). 인식 못 하면 null. */
        @Nullable
        public Integer floor;
        /** `B2F`, `3F` 등 표시용 층 라벨. 인식 못 하면 null. */
        @Nullable
        public String floorLabel;
        /** 구역/위치 표기(예: `A12`). 인식 못 하면 null. */
        @Nullable
        public String positionText;
        /** 배경(표지판) 대표 색 RGB(ARGB int). 판정 못 하면 null. */
        @Nullable
        public Integer bgColorRgb;
        /** 글자 대표 색 RGB(ARGB int). 판정 못 하면 null. */
        @Nullable
        public Integer textColorRgb;
        /** OCR 원문(디버깅/보조용). */
        @Nullable
        public String rawText;

        /**
         * 색 계산에 쓸 선택 글자 박스(층 줄 박스 + 위치 박스). analyzer 내부 전용 — 색을 뽑고 나면 의미 없어
         * UI/직렬화에서는 무시한다. 선택된 층/위치가 없으면 비어 있다.
         */
        @Nullable
        List<Rect> selectionBoxes;

        /** 층 정보가 인식되었는지 여부. */
        public boolean hasFloor() {
            return levelType != null && floorLabel != null;
        }

        /** RGB int를 `#RRGGBB` 16진 문자열로. */
        public static String hex(int rgb) {
            return String.format("#%06X", 0xFFFFFF & rgb);
        }

        /**
         * 사용자에게 보이는 메모(추가 위치 정보). 위치 텍스트만 담는다.
         * 색(hex)은 메모에 넣지 않고 별도 컬럼/스와치로만 노출한다.
         */
        @Nullable
        public String memoText() {
            return positionText != null && !positionText.isEmpty() ? positionText : null;
        }
    }

    public interface Callback {
        void onResult(@NonNull Result result);
    }

    // 숫자 1↔l/I/|, 0↔O/o OCR 혼동을 흡수하기 위해 라틴 층 숫자에 허용하는 문자 클래스.
    // 매치 후 normalizeFloorDigits로 실제 숫자로 정규화한다(예: BlF/BIF → B1F).
    private static final String FLOOR_DIGIT = "[0-9lI|Oo]";
    // 지하: B2, B2F, B 2 F / 지하2층, 지하 2 층 (B1F가 BlF/BIF로 읽혀도 인식)
    private static final Pattern P_UNDER_B = Pattern.compile("\\bB\\s?(" + FLOOR_DIGIT + "{1,2})\\s?F?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_UNDER_KO = Pattern.compile("지하\\s*([0-9]{1,2})\\s*층?");
    // 지상: 3F / 지상3층 / 3층 (1F가 lF/IF로 읽혀도 인식)
    private static final Pattern P_GROUND_F = Pattern.compile("\\b(" + FLOOR_DIGIT + "{1,2})\\s?F\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_GROUND_KO = Pattern.compile("지상\\s*([0-9]{1,2})\\s*층?");
    private static final Pattern P_GROUND_KO2 = Pattern.compile("(?<![0-9])([0-9]{1,2})\\s*층");
    // 위치: 영문 1글자+숫자(A12, A-12). 숫자부에 0을 O/o로 오인식하는 경우(G04→GO4)까지 허용하고
    // 뒤에서 0으로 정규화한다(단, 실제 숫자가 1자 이상 있어야 구역으로 인정).
    private static final Pattern P_ZONE_EN = Pattern.compile("\\b([A-Za-z])\\s?-?\\s?([0-9Oo]{1,3})\\b");
    // 한글 1글자+숫자(가12)
    private static final Pattern P_ZONE_KO = Pattern.compile("([가-힣])\\s?-?\\s?([0-9]{1,3})");
    // 방향(구역) 표기: EAST/WEST/NORTH/SOUTH. 위치 앞에 붙인다(예: EAST A01).
    private static final Pattern P_DIRECTION =
            Pattern.compile("\\b(EAST|WEST|NORTH|SOUTH)\\b", Pattern.CASE_INSENSITIVE);

    private ParkingPhotoAnalyzer() {
    }

    // 표지판이 옆으로 누운 사진(세로 촬영 등)에도 대응하기 위해 시도하는 회전 각(도).
    private static final int[] ROTATIONS = {0, 90, 270, 180};

    /**
     * 비트맵을 분석한다. 표지판 글자가 회전돼 있을 수 있으므로 0/90/270/180°를 차례로 OCR해
     * 가장 많은 정보를 얻은 회전을 채택한다(층+위치가 모두 잡히면 조기 종료).
     * 색은 글자 박스 주변(표지판 배경)과 글자 획을 분리해 각각 대표 RGB로 추출하고, 글자 박스를 못 찾으면
     * 사진 전체 평균색을 배경색 fallback으로 쓴다.
     */
    public static void analyze(@NonNull Bitmap bitmap, @NonNull Callback callback) {
        final Integer fallbackBg = classifyColor(bitmap);
        tryRotation(bitmap, 0, fallbackBg, null, callback);
    }

    /**
     * 실시간 프리뷰용 경량 분석. {@link #analyze}가 4방향 회전을 모두 OCR하는 것과 달리,
     * 이미 정방향으로 세운 프레임을 회전 없이 한 번만 OCR한다(프레임마다 호출되므로 빠르게).
     * 콜백은 ML Kit 규약상 호출한 스레드에서 전달된다(보통 메인 스레드).
     */
    public static void analyzeFrame(@NonNull Bitmap upright, @NonNull Callback callback) {
        final Integer fallbackBg = classifyColor(upright);
        TextRecognizer recognizer = TextRecognition.getClient(
                new KoreanTextRecognizerOptions.Builder().build());
        recognizer.process(InputImage.fromBitmap(upright, 0))
                .addOnSuccessListener(text -> {
                    recognizer.close();
                    Result r = parseText(text, upright.getWidth(), upright.getHeight());
                    RegionColors rc = classifyRegionColors(upright, r.selectionBoxes);
                    r.bgColorRgb = rc.background != null ? rc.background : fallbackBg;
                    r.textColorRgb = rc.text;
                    callback.onResult(r);
                })
                .addOnFailureListener(e -> {
                    recognizer.close();
                    Result r = new Result();
                    r.bgColorRgb = fallbackBg;
                    callback.onResult(r);
                });
    }

    private static void tryRotation(@NonNull Bitmap original, int index, @Nullable Integer fallbackBg,
                                    @Nullable Result best, @NonNull Callback callback) {
        if (index >= ROTATIONS.length) {
            emit(best, fallbackBg, callback);
            return;
        }
        int deg = ROTATIONS[index];
        final Bitmap rotated = deg == 0 ? original : rotate(original, deg);
        TextRecognizer recognizer = TextRecognition.getClient(
                new KoreanTextRecognizerOptions.Builder().build());
        recognizer.process(InputImage.fromBitmap(rotated, 0))
                .addOnSuccessListener(text -> {
                    recognizer.close();
                    Result r = parseText(text, rotated.getWidth(), rotated.getHeight());
                    RegionColors rc = classifyRegionColors(rotated, r.selectionBoxes);
                    r.bgColorRgb = rc.background;   // 표지판 배경색(없으면 emit 시 fallback)
                    r.textColorRgb = rc.text;       // 글자색
                    if (rotated != original) {
                        rotated.recycle();
                    }
                    Result picked = pickBetter(best, r);
                    // (#1) 층+위치가 모두 잡히면 남은 회전 OCR을 건너뛴다(불필요한 연산 절감).
                    if (picked.hasFloor() && picked.positionText != null) {
                        emit(picked, fallbackBg, callback);
                        return;
                    }
                    tryRotation(original, index + 1, fallbackBg, picked, callback);
                })
                .addOnFailureListener(e -> {
                    recognizer.close();
                    if (rotated != original) {
                        rotated.recycle();
                    }
                    tryRotation(original, index + 1, fallbackBg, best, callback);
                });
    }

    private static void emit(@Nullable Result best, @Nullable Integer fallbackBg,
                             @NonNull Callback callback) {
        Result r = best != null ? best : new Result();
        if (r.bgColorRgb == null) {
            r.bgColorRgb = fallbackBg; // 표지판 배경을 못 구하면 사진 전체 평균색
        }
        callback.onResult(r);
    }

    /** 더 많은 정보를 담은 결과를 고른다(층 > 위치 > 글자색 가중). 동률이면 기존(앞선 회전) 유지. */
    private static Result pickBetter(@Nullable Result a, @NonNull Result b) {
        if (a == null) {
            return b;
        }
        return score(b) > score(a) ? b : a;
    }

    private static int score(@NonNull Result r) {
        return (r.hasFloor() ? 4 : 0)
                + (r.positionText != null ? 2 : 0)
                + (r.textColorRgb != null ? 1 : 0);
    }

    private static Bitmap rotate(@NonNull Bitmap src, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    // ----- 텍스트 파싱 -----

    /** OCR 한 줄(텍스트 + 화면상 박스). 위치 선택을 2D 좌표 거리로 하기 위해 박스를 함께 들고 다닌다. */
    private static final class Line {
        final String text;
        @Nullable
        final Rect box;

        Line(@NonNull String text, @Nullable Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    /** 인식된 층 정보 + 화면상 위치(층 표기가 있던 줄의 박스). */
    private static final class FloorHit {
        ParkingLevelType level;
        int floor;
        String span;    // 매치된 원문(예: "B2", "지하2층") — 위치 탐색 시 가린다
        @Nullable
        Rect box;       // 층 표기가 있던 줄의 박스(위치와의 2D 거리 계산용)
    }

    @NonNull
    static Result parseText(@NonNull Text ocr, int imgW, int imgH) {
        Result r = new Result();
        r.rawText = ocr.getText();

        // 사용자가 표지판을 화면 중앙에 두고 찍으므로, 사진 중앙에 있는 글자에 우선순위를 준다.
        final int cx = imgW / 2;
        final int cy = imgH / 2;

        // 블록 단위로 줄을 모으고, 사진 중앙을 품거나 중앙에 가장 가까운 블록을 주 표지판(ROI)으로 본다. (#3)
        // 중앙 우선이라 상가 간판·번호판 등 주변 잡텍스트가 다른 블록에 있으면 자연히 밀려난다.
        List<List<Line>> blocks = new ArrayList<>();
        int primaryIdx = -1;
        boolean primaryContains = false;
        long primaryDist = Long.MAX_VALUE;
        long primaryArea = -1;
        for (Text.TextBlock block : ocr.getTextBlocks()) {
            List<Line> lines = new ArrayList<>();
            for (Text.Line line : block.getLines()) {
                lines.add(new Line(line.getText(), line.getBoundingBox()));
            }
            if (lines.isEmpty()) {
                continue;
            }
            blocks.add(lines);
            Rect bb = block.getBoundingBox();
            boolean contains = bb != null && bb.contains(cx, cy);
            long dist = centerDist(bb, cx, cy);
            long area = bb != null ? (long) bb.width() * bb.height() : 0;
            // 우선순위: 중앙 포함 > 중앙에 더 가까움 > 더 큰 면적
            boolean better;
            if (primaryIdx < 0) {
                better = true;
            } else if (contains != primaryContains) {
                better = contains;
            } else if (dist != primaryDist) {
                better = dist < primaryDist;
            } else {
                better = area > primaryArea;
            }
            if (better) {
                primaryIdx = blocks.size() - 1;
                primaryContains = contains;
                primaryDist = dist;
                primaryArea = area;
            }
        }
        if (blocks.isEmpty()) {
            return r;
        }

        // 1) 층 인식: 주 표지판(ROI)에서 먼저 찾고, 없으면 전체 텍스트로 확장. (#3)
        //    같은 패턴이 여러 줄에서 잡히면 사진 중앙에 가장 가까운 줄을 채택한다.
        List<Line> primary = blocks.get(primaryIdx);
        List<Line> all = flatten(blocks);
        FloorHit fh = detectFloor(primary, cx, cy);
        List<Line> scope = primary;
        if (fh == null) {
            fh = detectFloor(all, cx, cy);
            scope = all;
        }
        if (fh != null) {
            r.levelType = fh.level;
            r.floor = fh.floor;
            r.floorLabel = fh.level == ParkingLevelType.UNDERGROUND
                    ? ("B" + fh.floor + "F") : (fh.floor + "F");
        }

        // 2) 위치: 층을 찾은 범위(ROI) 안에서 구역 후보를 먼저 찾는다. 층을 찾았으면 층 줄과의 2D 거리(#2),
        //    못 찾았으면 사진 중앙과의 거리로 고른다. ROI(주 블록)에서 못 찾으면 전체 텍스트로 확장 —
        //    표지판에서 층 숫자와 베이 번호(C2 등)가 다른 블록으로 나뉘어 ROI 밖에 있을 때 회수한다.
        Rect floorBox = fh != null ? fh.box : null;
        String floorSpan = fh != null ? fh.span : null;
        PositionHit ph = findPosition(scope, floorBox, floorSpan, cx, cy);
        if (ph == null && scope != all) {
            ph = findPosition(all, floorBox, floorSpan, cx, cy);
        }
        String zone = ph != null ? ph.text : null;
        // 방향(EAST/WEST/…)은 구역(A01·C2 등)과 합쳐질 때만 의미가 있다. 방향만 단독이면 위치로 보지 않는다.
        String dir = findDirection(ocr.getText().replace('\n', ' '));
        if (dir != null && zone != null && !zone.isEmpty()) {
            r.positionText = dir + " " + zone;
        } else {
            r.positionText = zone;
        }

        // 색 계산 대상: '선택된' 층 줄 박스 + 위치 박스만(전체 글자가 아니라). 같은 줄이면 한 번만.
        List<Rect> boxes = new ArrayList<>();
        if (floorBox != null) {
            boxes.add(floorBox);
        }
        Rect posBox = ph != null ? ph.box : null;
        if (posBox != null && !posBox.equals(floorBox)) {
            boxes.add(posBox);
        }
        r.selectionBoxes = boxes;
        return r;
    }

    private static List<Line> flatten(@NonNull List<List<Line>> blocks) {
        List<Line> out = new ArrayList<>();
        for (List<Line> b : blocks) {
            out.addAll(b);
        }
        return out;
    }

    /**
     * 층 인식. 패턴 우선순위(지하한글 > 지상한글 > N층 > Bx > xF)대로 각 패턴을 모든 줄에서 찾는다.
     * "B02" 같은 구역 라벨이 지하 층으로 오인되지 않도록 명시적 한글 층을 먼저 본다.
     */
    @Nullable
    private static FloorHit detectFloor(@NonNull List<Line> lines, int cx, int cy) {
        FloorHit hit;
        if ((hit = matchFloor(lines, P_UNDER_KO, ParkingLevelType.UNDERGROUND, cx, cy)) != null) return hit;
        if ((hit = matchFloor(lines, P_GROUND_KO, ParkingLevelType.GROUND, cx, cy)) != null) return hit;
        if ((hit = matchFloor(lines, P_GROUND_KO2, ParkingLevelType.GROUND, cx, cy)) != null) return hit;
        if ((hit = matchFloor(lines, P_UNDER_B, ParkingLevelType.UNDERGROUND, cx, cy)) != null) return hit;
        if ((hit = matchFloor(lines, P_GROUND_F, ParkingLevelType.GROUND, cx, cy)) != null) return hit;
        return null;
    }

    /** 패턴에 매치되는 줄 중 사진 중앙에 가장 가까운 것을 채택(여러 표지판 중 정조준한 것 우선). */
    @Nullable
    private static FloorHit matchFloor(@NonNull List<Line> lines, @NonNull Pattern p,
                                       @NonNull ParkingLevelType level, int cx, int cy) {
        FloorHit best = null;
        long bestDist = Long.MAX_VALUE;
        for (Line line : lines) {
            Matcher m = p.matcher(line.text);
            if (m.find()) {
                Integer floor = parseIntSafe(normalizeFloorDigits(m.group(1)));
                if (floor == null || floor < 1) {
                    continue;
                }
                long dist = centerDist(line.box, cx, cy);
                if (best == null || dist < bestDist) {
                    best = new FloorHit();
                    best.level = level;
                    best.floor = floor;
                    best.span = m.group();
                    best.box = line.box;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    /** 라틴 층 숫자의 OCR 혼동을 보정: l/I/| → 1, O/o → 0 (대소문자 무관). */
    @NonNull
    private static String normalizeFloorDigits(@NonNull String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'l': case 'L': case 'I': case 'i': case '|':
                    sb.append('1');
                    break;
                case 'O': case 'o':
                    sb.append('0');
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** EAST/WEST/NORTH/SOUTH 중 처음 인식된 방향을 대문자로 반환(없으면 null). */
    @Nullable
    private static String findDirection(@NonNull String text) {
        Matcher m = P_DIRECTION.matcher(text);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** 첫 등장하는 span을 같은 길이의 공백으로 치환해 다른 매치의 인덱스를 보존한다. */
    @NonNull
    private static String blankSpan(@NonNull String text, @Nullable String span) {
        if (span == null || span.isEmpty()) {
            return text;
        }
        int idx = text.indexOf(span);
        if (idx < 0) {
            return text;
        }
        char[] spaces = new char[span.length()];
        java.util.Arrays.fill(spaces, ' ');
        return text.substring(0, idx) + new String(spaces) + text.substring(idx + span.length());
    }

    private static final Pattern P_NUMBER = Pattern.compile("\\b([0-9]{1,3})\\b");
    // 영숫자 연속 토큰(구역 반복 회수용). 벽면에 같은 코드가 붙어 G04G04처럼 한 덩어리로 읽힐 때 쓴다.
    private static final Pattern P_ALNUM = Pattern.compile("[A-Za-z0-9]+");

    /**
     * 위치(구역) 후보를 줄 단위로 찾는다. 각 후보의 거리는 층을 찾았으면 층 줄과의 2D 거리(#2),
     * 못 찾았으면 사진 중앙과의 거리로 본다(중앙 우선).
     * 우선순위:
     * 1) 영문 1글자+숫자(A12) — 빈도 우선, 동률이면 거리 최단
     * 2) 한글 1글자+숫자(가12) — 거리 최단
     * 3) 1~3자리 숫자 — 거리 최단(번호판 등 먼 숫자 배제)
     */
    /** 선택된 위치(구역) 텍스트 + 그 글자 줄의 화면상 박스(색 표본 영역용). */
    private static final class PositionHit {
        final String text;
        @Nullable
        final Rect box;

        PositionHit(@NonNull String text, @Nullable Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    /**
     * '주기적으로 반복된' 영숫자 토큰에서 한 구역 단위를 회수한다(예: G04G04·04G04·4G04 → G04).
     * 벽면에 같은 코드가 연달아 있어 OCR이 한 덩어리로 읽은 경우만을 노린다.
     * 안전장치(사이드이펙트 방지):
     *  - 길이 4 미만은 무시(A12 등 단일 구역은 건드리지 않음)
     *  - 토큰의 '테두리'(longestBorder>0 = tail이 head와 일치)가 있어야만, 즉 <b>주기적일 때만</b> 동작
     *  - 반복 주기(p)는 구역 단위 길이(글자+숫자 1~3 = 2~4)일 때만 인정
     *  - 한 주기 창을 훑어 '글자+숫자' 구역이 되는 첫 위치를 채택(F/B·숫자없음 제외)
     */
    @Nullable
    private static String recoverRepeatedZone(@NonNull String token) {
        int n = token.length();
        if (n < 4) {
            return null; // 단일 구역/짧은 토큰은 대상 아님
        }
        int border = longestBorder(token);
        if (border <= 0) {
            return null; // 주기(반복)가 아니면 스킵 → 일반 문자열엔 영향 없음
        }
        int p = n - border; // 반복 주기(한 단위 길이)
        if (p < 2 || p > 4) {
            return null; // 구역 단위(글자1 + 숫자1~3)만 허용
        }
        for (int i = 0; i + p <= n; i++) {
            char letter = token.charAt(i);
            boolean isLatin = (letter >= 'A' && letter <= 'Z') || (letter >= 'a' && letter <= 'z');
            if (!isLatin) {
                continue;
            }
            char up = Character.toUpperCase(letter);
            if (up == 'F' || up == 'B') {
                continue; // 층 표기 잔재 회피(기존 규칙과 동일)
            }
            String digits = token.substring(i + 1, i + p);
            boolean ok = true;
            boolean hasDigit = false;
            for (int k = 0; k < digits.length(); k++) {
                char c = digits.charAt(k);
                if (Character.isDigit(c)) {
                    hasDigit = true;
                } else if (c != 'O' && c != 'o') { // O/o는 0 오인식으로 허용
                    ok = false;
                    break;
                }
            }
            if (ok && hasDigit) {
                return up + digits.replace('O', '0').replace('o', '0');
            }
        }
        return null;
    }

    /** KMP 실패함수로 문자열의 가장 긴 '테두리'(진부분 접두=접미) 길이. tail이 head와 겹치는 길이. */
    private static int longestBorder(@NonNull String s) {
        int n = s.length();
        int[] f = new int[n];
        int k = 0;
        for (int i = 1; i < n; i++) {
            while (k > 0 && s.charAt(i) != s.charAt(k)) {
                k = f[k - 1];
            }
            if (s.charAt(i) == s.charAt(k)) {
                k++;
            }
            f[i] = k;
        }
        return f[n - 1];
    }

    @Nullable
    private static PositionHit findPosition(@NonNull List<Line> lines, @Nullable Rect floorBox,
                                            @Nullable String floorSpan, int cx, int cy) {
        // 1) 영문 1글자+숫자. 여러 면/회전으로 같은 구역이 반복 인식되므로 가장 자주 나온 구역 채택,
        //    동률이면 거리(층 또는 중앙)로 가장 가까운 것. 채택 시 그 줄의 박스도 함께 보관(색 표본용).
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Map<String, Long> minDist = new java.util.HashMap<>();
        java.util.Map<String, Rect> zoneBox = new java.util.HashMap<>();
        for (Line line : lines) {
            String text = blankSpan(line.text, floorSpan); // 같은 줄에 섞인 층 표기는 가린다
            long dist = distanceTo(line.box, floorBox, cx, cy);
            Matcher en = P_ZONE_EN.matcher(text);
            while (en.find()) {
                String letter = en.group(1).toUpperCase();
                if (letter.equals("F") || letter.equals("B")) {
                    continue; // 층 표기 잔재(F/B) 회피
                }
                String digits = en.group(2);
                if (digits.chars().noneMatch(Character::isDigit)) {
                    continue; // 'O'만 있는 경우(GO, TO 등)는 구역 아님
                }
                String zone = letter + digits.replace('O', '0').replace('o', '0'); // 0 오인식 보정
                counts.merge(zone, 1, Integer::sum);
                Long prev = minDist.get(zone);
                if (prev == null || dist < prev) {
                    minDist.put(zone, dist);
                    zoneBox.put(zone, line.box);
                }
            }
            // 반복 구역 회수: 벽면에 같은 코드가 연달아 있어 G04G04/04G04/4G04처럼 붙어 읽힌 토큰은
            // 위 정규식이 단어경계 때문에 놓친다. 토큰이 '주기적'(tail이 head와 일치)일 때만 한 단위(G04)를
            // 회수해 후보에 '추가'한다. 주기가 아니면 건드리지 않으므로 일반 문자열엔 영향 없음.
            Matcher alnum = P_ALNUM.matcher(text);
            while (alnum.find()) {
                String zone = recoverRepeatedZone(alnum.group());
                if (zone != null) {
                    counts.merge(zone, 1, Integer::sum);
                    Long prev = minDist.get(zone);
                    if (prev == null || dist < prev) {
                        minDist.put(zone, dist);
                        zoneBox.put(zone, line.box);
                    }
                }
            }
        }
        String bestZone = pickByFrequency(counts, minDist);
        if (bestZone != null) {
            return new PositionHit(bestZone, zoneBox.get(bestZone));
        }
        // 2) 한글 1글자+숫자 중 거리(층 또는 중앙)가 가장 가까운 것
        long bestDist = Long.MAX_VALUE;
        Rect bestBox = null;
        for (Line line : lines) {
            String text = blankSpan(line.text, floorSpan);
            long dist = distanceTo(line.box, floorBox, cx, cy);
            Matcher ko = P_ZONE_KO.matcher(text);
            while (ko.find()) {
                if (dist < bestDist) {
                    bestDist = dist;
                    bestZone = ko.group(1) + ko.group(2);
                    bestBox = line.box;
                }
            }
        }
        if (bestZone != null) {
            return new PositionHit(bestZone, bestBox);
        }
        // 3) 순수 숫자: 거리(층 또는 중앙)가 가장 가까운 1~3자리 수(번호판 등 먼 숫자 배제)
        String bestNum = null;
        bestDist = Long.MAX_VALUE;
        bestBox = null;
        for (Line line : lines) {
            String text = blankSpan(line.text, floorSpan);
            long dist = distanceTo(line.box, floorBox, cx, cy);
            Matcher num = P_NUMBER.matcher(text);
            while (num.find()) {
                if (dist < bestDist) {
                    bestDist = dist;
                    bestNum = num.group(1);
                    bestBox = line.box;
                }
            }
        }
        return bestNum != null ? new PositionHit(bestNum, bestBox) : null;
    }

    /**
     * 후보 줄의 거리(제곱; 비교용이라 sqrt 생략).
     * 층 박스를 알면 층 줄과의 2D 거리(#2), 모르면 사진 중앙과의 거리(중앙 우선).
     */
    private static long distanceTo(@Nullable Rect lineBox, @Nullable Rect floorBox, int cx, int cy) {
        if (lineBox == null) {
            return Long.MAX_VALUE / 2;
        }
        if (floorBox != null) {
            long dx = lineBox.centerX() - floorBox.centerX();
            long dy = lineBox.centerY() - floorBox.centerY();
            return dx * dx + dy * dy;
        }
        return centerDist(lineBox, cx, cy); // 층을 못 찾았으면 중앙에 가까운 후보 우선
    }

    /** 박스 중심과 (cx,cy) 사이의 2D 거리(제곱). 박스가 없으면 최댓값. */
    private static long centerDist(@Nullable Rect box, int cx, int cy) {
        if (box == null) {
            return Long.MAX_VALUE;
        }
        long dx = box.centerX() - cx;
        long dy = box.centerY() - cy;
        return dx * dx + dy * dy;
    }

    /** 빈도 최댓값(동률이면 층과 더 가까운 것)으로 구역을 고른다. */
    @Nullable
    private static String pickByFrequency(@NonNull java.util.Map<String, Integer> counts,
                                          @NonNull java.util.Map<String, Long> minDist) {
        String best = null;
        int bestCount = -1;
        long bestD = Long.MAX_VALUE;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            int c = e.getValue();
            long d = minDist.get(e.getKey());
            if (c > bestCount || (c == bestCount && d < bestD)) {
                bestCount = c;
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    @Nullable
    private static Integer parseIntSafe(@Nullable String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----- 색 추출(대표 RGB) -----

    /** 비트맵 전체를 표본 추출한 평균 RGB. 글자 박스를 못 찾을 때 배경색 fallback으로 쓴다. */
    @Nullable
    static Integer classifyColor(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, h / 64);
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bitmap.getPixel(x, y);
                sr += Color.red(c);
                sg += Color.green(c);
                sb += Color.blue(c);
                n++;
            }
        }
        return n > 0 ? Color.rgb((int) (sr / n), (int) (sg / n), (int) (sb / n)) : null;
    }

    /** 글자 박스 영역 분석으로 얻은 표지판 배경/글자 대표 RGB(없으면 null). */
    private static final class RegionColors {
        @Nullable
        Integer background;
        @Nullable
        Integer text;
    }

    /**
     * '선택된' 층/위치 글자 박스에서만 표지판 배경색과 글자색의 대표 RGB를 추출한다(전체 글자가 아님).
     *  - 글자색: 박스 <b>안쪽</b> 픽셀 중, (링에서 추정한) 지역 배경 명도와 대비가 큰(≥55) 픽셀 평균
     *  - 배경색: 박스 <b>경계 바로 바깥의 얇은 링</b> 픽셀에서, 명도 최빈값(±30) 평균
     * 박스가 없거나 표본이 부족하면 해당 색은 null(상위에서 전체 평균색 fallback).
     */
    @NonNull
    static RegionColors classifyRegionColors(@NonNull Bitmap bitmap, @Nullable List<Rect> boxes) {
        RegionColors out = new RegionColors();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0 || boxes == null || boxes.isEmpty()) {
            return out;
        }
        List<Integer> ring = new ArrayList<>();   // 박스 경계 바로 바깥(배경)
        List<Integer> inner = new ArrayList<>();  // 박스 안쪽(글자+지역 배경)
        for (Rect r : boxes) {
            if (r == null || r.width() <= 0 || r.height() <= 0) {
                continue;
            }
            int t = Math.max(2, r.height() / 7); // 링 두께 ≈ 글자 높이의 15%
            int left = Math.max(0, r.left - t);
            int top = Math.max(0, r.top - t);
            int right = Math.min(w, r.right + t);
            int bottom = Math.min(h, r.bottom + t);
            if (right <= left || bottom <= top) {
                continue;
            }
            int sx = Math.max(1, (right - left) / 48);
            int sy = Math.max(1, (bottom - top) / 48);
            for (int y = top; y < bottom; y += sy) {
                for (int x = left; x < right; x += sx) {
                    int c = bitmap.getPixel(x, y);
                    if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) {
                        inner.add(c);
                    } else {
                        ring.add(c); // 박스 바로 바깥 링
                    }
                }
            }
        }
        // 배경색: 링의 지역 배경 명도(최빈값) ±30 평균
        Integer bgLum = modalLuma(ring);
        if (bgLum != null) {
            ColorAvg bg = new ColorAvg();
            for (int c : ring) {
                if (Math.abs(luma(c) - bgLum) <= 30) {
                    bg.add(c);
                }
            }
            out.background = bg.average();
        }
        // 글자색: 박스 안쪽에서 배경 명도와 대비 큰(≥55) 픽셀 중 더 많은 쪽 평균
        if (bgLum != null && !inner.isEmpty()) {
            ColorAvg bright = new ColorAvg(); // 배경보다 밝은 글자
            ColorAvg dark = new ColorAvg();   // 배경보다 어두운 글자
            for (int c : inner) {
                int d = luma(c) - bgLum;
                if (d >= 55) {
                    bright.add(c);
                } else if (d <= -55) {
                    dark.add(c);
                }
            }
            ColorAvg textSide = bright.count >= dark.count ? bright : dark;
            out.text = textSide.average();
        }
        return out;
    }

    /** 표본 픽셀들의 명도 최빈값(히스토그램 mode). 비어 있으면 null. */
    @Nullable
    private static Integer modalLuma(@NonNull List<Integer> samples) {
        if (samples.isEmpty()) {
            return null;
        }
        int[] hist = new int[256];
        for (int c : samples) {
            hist[luma(c)]++;
        }
        int best = 0;
        for (int i = 1; i < 256; i++) {
            if (hist[i] > hist[best]) {
                best = i;
            }
        }
        return best;
    }

    /** ITU-R BT.601 휘도. */
    private static int luma(int color) {
        return (int) (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color));
    }

    /** 픽셀 RGB 평균 누적기. */
    private static final class ColorAvg {
        private long sr;
        private long sg;
        private long sb;
        private int count;

        void add(int color) {
            sr += Color.red(color);
            sg += Color.green(color);
            sb += Color.blue(color);
            count++;
        }

        @Nullable
        Integer average() {
            return count > 0 ? Color.rgb((int) (sr / count), (int) (sg / count), (int) (sb / count)) : null;
        }
    }
}
