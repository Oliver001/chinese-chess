package chess;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * 开局库 & 残局库
 *
 * 双层架构：
 * 1. 优先在线查询 chessdb.cn 云库（覆盖全量开局/残局）
 *    - 棋子数 <= EGTB_THRESHOLD 时使用残局库（DTC/DTM）
 *    - 否则使用开局库
 * 2. 在线超时/失败时，回退到本地离线开局库（50+ 主流变例）
 *
 * 走法格式（云库 UCCI 坐标系）：
 *   列 a~i 对应我们的 col 0~8
 *   行 0~9 对应我们的 row 0~9（黑方在0，红方在9）
 *   例：b2e2 → fromCol=1,fromRow=7,toCol=4,toRow=7  (红炮二平五)
 */
public class OpeningBook {

    // =====================================================================
    // 开局库模式配置（可在运行时切换）
    // =====================================================================
    public enum BookMode {
        CLOUD_AND_LOCAL("云库+本地库（推荐）", "优先查询 chessdb.cn 云库，失败时回退本地库"),
        LOCAL_ONLY     ("仅本地库",            "只使用内置本地开局库，不联网"),
        DISABLED       ("禁用开局库",          "不使用任何开局库，完全由AI搜索决策");

        public final String label;
        public final String desc;
        BookMode(String l, String d) { this.label = l; this.desc = d; }
        @Override public String toString() { return label; }
    }

    /** 当前开局库模式（全局，默认云库+本地） */
    public static volatile BookMode currentMode = BookMode.CLOUD_AND_LOCAL;

    /** 在线查询超时（毫秒）*/
    private static final int ONLINE_TIMEOUT_MS = 3000;

    /** 云库 API 地址 */
    private static final String API_URL =
        "https://www.chessdb.cn/chessdb.php";

    // =====================================================================
    // 查询结果（带来源标记）
    // =====================================================================
    public static class LookupResult {
        public final int[] move;       // {fromRow, fromCol, toRow, toCol}
        public final boolean fromCloud; // true=云库, false=本地开局库
        public LookupResult(int[] move, boolean fromCloud) {
            this.move = move;
            this.fromCloud = fromCloud;
        }
    }

    // =====================================================================
    // 公共入口
    // =====================================================================

    /**
     * 旧接口（兼容）：只返回走法数组
     */
    public static int[] lookup(Board board, boolean isRed) {
        LookupResult r = lookupWithSource(board, isRed);
        return r != null ? r.move : null;
    }

    /**
     * 新接口：返回 LookupResult（包含走法和来源），null 表示无推荐
     * 根据 currentMode 决定查询策略：
     * - CLOUD_AND_LOCAL：优先云库，失败回退本地库
     * - LOCAL_ONLY：仅本地库
     * - DISABLED：直接返回 null
     */
    public static LookupResult lookupWithSource(Board board, boolean isRed) {
        if (currentMode == BookMode.DISABLED) return null;

        if (currentMode == BookMode.CLOUD_AND_LOCAL) {
            // --- 在线查询 ---
            // 使用 toUCCIFEN：棋子段同 toFEN，但走棋方标记 b=红方/w=黑方（chessdb约定）
            try {
                String fen = board.toUCCIFEN(isRed);
                String fenEncoded = URLEncoder.encode(fen, "UTF-8");
                String urlStr = API_URL + "?action=queryall&board=" + fenEncoded
                              + "&learn=0&egtbmetric=dtm";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(ONLINE_TIMEOUT_MS);
                conn.setReadTimeout(ONLINE_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "ChessJavaDemo/1.0");

                int status = conn.getResponseCode();
                if (status == 200) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String resp = br.readLine();
                    br.close();
                    conn.disconnect();

                    if (resp != null && !resp.startsWith("unknown") &&
                        !resp.startsWith("invalid") && !resp.startsWith("nobestmove") &&
                        !resp.startsWith("checkmate") && !resp.startsWith("stalemate")) {

                        int[] mv = parseBestFromQueryAll(resp);
                        if (mv != null && isLegalMove(board, mv, isRed)) {
                            return new LookupResult(mv, true);
                        }
                        // 云库走法非法，静默回退到本地库
                    }
                } else {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
                // 网络不可用或超时，静默回退到本地库
            }
        }

        // --- 本地离线开局库兜底 ---
        if (currentMode != BookMode.DISABLED) {
            int[] mv = localLookup(board, isRed);
            return mv != null ? new LookupResult(mv, false) : null;
        }
        return null;
    }

    /**
     * 从 queryall 返回结果中选最优走法
     * 格式：move:b2e2,score:1,rank:2,note:...,winrate:50.08|move:...
     *
     * 策略：
     *   1. score 有具体值的走法优先（rank 直接用）
     *   2. score=?? 的走法 rank 加 1000 降级（兜底，不丢弃）
     *   3. 所有走法中取调整后 rank 最小的，相同时随机选一个
     *
     * 修复说明（v19）：
     *   原来 scoreKnown=true 是必要条件，冷门局面（score=??）整批走法被丢弃，
     *   导致"开局库只生效一步"。现在 score=?? 的走法通过降级 rank+1000 保留为兜底选项。
     */
    private static int[] parseBestFromQueryAll(String resp) {
        try {
            String[] entries = resp.trim().split("\\|");
            // {moveStr, adjustedRank} 的所有走法列表
            List<String[]> known = new ArrayList<>();
            int bestRank = Integer.MAX_VALUE;

            for (String entry : entries) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                String moveStr = null;
                int rank = Integer.MAX_VALUE;
                boolean scoreKnown = false;

                for (String part : entry.split(",")) {
                    part = part.trim();
                    if (part.startsWith("move:") || part.startsWith("egtb:")) {
                        moveStr = part.substring(part.indexOf(':') + 1).trim();
                    } else if (part.startsWith("rank:")) {
                        try { rank = Integer.parseInt(part.substring(5).trim()); }
                        catch (NumberFormatException ignored2) {}
                    } else if (part.startsWith("score:")) {
                        String s = part.substring(6).trim();
                        scoreKnown = !s.equals("??");
                    }
                }

                if (moveStr != null && moveStr.matches("[a-i][0-9][a-i][0-9]")) {
                    // rank 缺失时给默认值 999
                    if (rank == Integer.MAX_VALUE) rank = 999;
                    // score=?? 降级：rank+1000（有具体 score 的走法优先）
                    if (!scoreKnown) rank += 1000;
                    if (rank < bestRank) bestRank = rank;
                    known.add(new String[]{moveStr, String.valueOf(rank)});
                }
            }

            if (known.isEmpty()) return null;

            // 收集所有 bestRank 的走法，随机选一个
            final int finalBestRank = bestRank;
            List<String> candidates = new ArrayList<>();
            for (String[] kv : known) {
                if (Integer.parseInt(kv[1]) == finalBestRank) {
                    candidates.add(kv[0]);
                }
            }

            String chosen = candidates.get(RNG.nextInt(candidates.size()));
            return ucciToRowCol(chosen);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * UCCI/chessdb.cn 坐标 → {fromRow, fromCol, toRow, toCol}
     * chessdb.cn 坐标系：列 a(0)~i(8)，行 0(黑方底线=row0) ~ 9(红方底线=row9)
     * 我们的内部坐标系：row=0(黑方底线), row=9(红方底线) —— 与 UCCI 行号完全一致，直接映射
     * 注意：皮卡鱼 UCI-Cyclone 坐标系不同（rank0=红方底线），转换在 iccsToInternal() 中处理
     */
    private static int[] ucciToRowCol(String ucci) {
        int fc = ucci.charAt(0) - 'a';
        int fr = ucci.charAt(1) - '0';   // UCCI行0=黑方底线=内部row0，直接映射
        int tc = ucci.charAt(2) - 'a';
        int tr = ucci.charAt(3) - '0';
        if (fc<0||fc>8||fr<0||fr>9||tc<0||tc>8||tr<0||tr>9) return null;
        return new int[]{fr, fc, tr, tc};
    }

    /**
     * 验证走法是否在象棋规则上合法：
     * 1. 起点有棋子且属于 isRed 方
     * 2. 终点出现在 getLegalMoves(fr, fc) 列表中（满足棋子走法规则且不自将）
     * 注意：board 对象在验证过程中会被临时修改再还原（getLegalMoves内部逻辑），
     *       为安全起见此处在副本上操作。
     */
    private static boolean isLegalMove(Board board, int[] mv, boolean isRed) {
        if (mv == null || mv.length < 4) return false;
        int fr = mv[0], fc = mv[1], tr = mv[2], tc = mv[3];
        Piece movingPiece = board.getPiece(fr, fc);
        if (movingPiece == null || movingPiece.isRed != isRed) return false;
        // 调用 getLegalMoves 会在 board 上做 move/undo，需要用副本以避免并发问题
        Board copy = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                copy.grid[r][c] = board.grid[r][c] != null ? board.grid[r][c].copy() : null;
        for (int[] legal : copy.getLegalMoves(fr, fc)) {
            if (legal[0] == tr && legal[1] == tc) return true;
        }
        return false;
    }



    // =====================================================================
    // 本地离线开局库
    // =====================================================================

    /** Zobrist 哈希表（必须先于 BOOK 初始化） */
    private static final long[][][][] ZOBRIST;
    private static final long ZOBRIST_SIDE;

    static {
        ZOBRIST = new long[10][9][7][2];
        Random rng = new Random(12345678L);
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                for (int t = 0; t < 7; t++)
                    for (int s = 0; s < 2; s++)
                        ZOBRIST[r][c][t][s] = rng.nextLong();
        ZOBRIST_SIDE = rng.nextLong();
    }

    /** 本地开局库：Zobrist → 候选走法列表（带权重） */
    private static final Map<Long, List<int[]>> BOOK;
    private static final Random RNG = new Random(42);

    static {
        BOOK = new HashMap<>();
        buildLocalBook();
    }

    private static int[] localLookup(Board board, boolean isRed) {
        long hash = zobrist(board, isRed);
        List<int[]> candidates = BOOK.get(hash);
        if (candidates == null || candidates.isEmpty()) return null;
        // 按权重加权随机选择
        int totalWeight = 0;
        for (int[] c : candidates) totalWeight += c[4];
        int pick = RNG.nextInt(totalWeight);
        int acc = 0;
        for (int[] c : candidates) {
            acc += c[4];
            if (pick < acc) return new int[]{c[0], c[1], c[2], c[3]};
        }
        return candidates.get(0);
    }

    public static long zobrist(Board board, boolean redTurn) {
        long h = redTurn ? ZOBRIST_SIDE : 0L;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = board.getPiece(r, c);
                if (p != null)
                    h ^= ZOBRIST[r][c][p.type.ordinal()][p.isRed ? 1 : 0];
            }
        return h;
    }

    // =====================================================================
    // 本地开局库构建
    // 覆盖主流开局：当头炮、屏风马、顺炮、反宫马、仙人指路、飞象局、边炮等
    // 走法格式：{fromRow, fromCol, toRow, toCol, weight}
    // weight 越大被选中概率越高（主流走法设高权重）
    // =====================================================================
    private static void buildLocalBook() {
        Board init = new Board();

        // ================================================================
        // 初始局面：红方第一手（经典开局）
        // ================================================================
        addW(init, true,
            mw(7,1,7,4, 40),   // 炮二平五（当头炮，最常见）
            mw(7,7,7,4, 30),   // 炮八平五（中炮）
            mw(9,1,7,2, 15),   // 马二进三
            mw(9,7,7,6, 15),   // 马八进七
            mw(6,2,5,2, 10),   // 兵三进一（仙人指路）
            mw(6,6,5,6, 10),   // 兵七进一
            mw(9,2,7,4, 8),    // 相三进五（飞象局）
            mw(9,6,7,4, 8),    // 相七进五
            mw(7,1,7,0, 4),    // 炮二平一（边炮）
            mw(7,7,7,8, 4)     // 炮八平九（边炮）
        );

        // ================================================================
        // 当头炮（炮二平五）分支
        // ================================================================
        Board c1 = play(new Board(),
            mv(7,1,7,4));  // 红：炮二平五

        if (c1 != null) {
            // 黑方应对
            addW(c1, false,
                mw(0,7,1,5, 35),   // 马8进7（最常见）
                mw(0,1,2,2, 30),   // 马2进3
                mw(2,7,2,4, 20),   // 炮8平5（顺炮）
                mw(3,4,4,4, 10),   // 卒5进1
                mw(0,0,0,1, 5)     // 车9平8
            );

            // ---- 当头炮 vs 马8进7 ----
            Board c1a = play(cp(c1), mv(0,7,1,5));
            if (c1a != null) {
                addW(c1a, true,
                    mw(9,1,7,2, 40),  // 马二进三（最常见）
                    mw(9,7,7,6, 30),  // 马八进七
                    mw(6,2,5,2, 15),  // 兵三进一
                    mw(7,7,7,4, 15)   // 炮八平五（重炮）
                );

                // 马二进三 → 马8进7 之后
                Board c2 = play(cp(c1a), mv(9,1,7,2));
                if (c2 != null) {
                    addW(c2, false,
                        mw(0,1,2,2, 40),  // 马2进3（屏风马）
                        mw(0,0,0,2, 25),  // 车9平8
                        mw(2,1,2,4, 20),  // 炮2平5（顺炮变体）
                        mw(3,4,4,4, 15)   // 卒5进1
                    );

                    // 屏风马分支（红马二进三 → 黑马2进3）
                    Board c3 = play(cp(c2), mv(0,1,2,2));
                    if (c3 != null) {
                        addW(c3, true,
                            mw(9,7,7,6, 45),  // 马八进七（双正马对屏风马）
                            mw(6,2,5,2, 25),  // 兵三进一
                            mw(6,6,5,6, 20),  // 兵七进一
                            mw(9,0,8,0, 10)   // 车一进一
                        );

                        // 马八进七 → 黑方后续
                        Board c4 = play(cp(c3), mv(9,7,7,6));
                        if (c4 != null) {
                            addW(c4, false,
                                mw(0,0,0,2, 35),  // 车9平8
                                mw(2,7,1,7, 30),  // 炮8进1（过河炮）
                                mw(3,4,4,4, 20),  // 卒5进1
                                mw(0,8,0,6, 15)   // 车1平3
                            );
                        }
                    }
                }

                // 马八进七 → 黑方接应
                Board c2b = play(cp(c1a), mv(9,7,7,6));
                if (c2b != null) {
                    addW(c2b, false,
                        mw(0,1,2,2, 40),  // 马2进3
                        mw(2,1,2,4, 30),  // 炮2平5
                        mw(0,0,0,2, 20),  // 车9平8
                        mw(3,4,4,4, 10)   // 卒5进1
                    );
                }
            }

            // ---- 当头炮 vs 马2进3 ----
            Board c1b = play(cp(c1), mv(0,1,2,2));
            if (c1b != null) {
                addW(c1b, true,
                    mw(9,7,7,6, 40),  // 马八进七（最常见应对）
                    mw(9,1,7,2, 35),  // 马二进三
                    mw(6,6,5,6, 15),  // 兵七进一
                    mw(7,7,7,4, 10)   // 炮八平五
                );
            }

            // ---- 当头炮 vs 炮8平5（顺炮） ----
            Board c1c = play(cp(c1), mv(2,7,2,4));
            if (c1c != null) {
                addW(c1c, true,
                    mw(9,1,7,2, 40),   // 马二进三
                    mw(6,2,5,2, 30),   // 兵三进一
                    mw(9,7,7,6, 20),   // 马八进七
                    mw(9,0,8,0, 10)    // 车一进一
                );

                // 顺炮：红马二进三
                Board sq2 = play(cp(c1c), mv(9,1,7,2));
                if (sq2 != null) {
                    addW(sq2, false,
                        mw(0,7,1,5, 40),  // 马8进7
                        mw(0,1,2,2, 30),  // 马2进3
                        mw(3,4,4,4, 20),  // 卒5进1
                        mw(0,0,0,2, 10)   // 车9平8
                    );
                }
            }

            // ---- 当头炮 vs 卒5进1 ----
            Board c1d = play(cp(c1), mv(3,4,4,4));
            if (c1d != null) {
                addW(c1d, true,
                    mw(9,1,7,2, 40),
                    mw(9,7,7,6, 35),
                    mw(6,2,5,2, 25)
                );
            }
        }

        // ================================================================
        // 炮八平五（中炮对称变体，黑方先手）
        // ================================================================
        Board m2 = play(new Board(), mv(7,7,7,4));
        if (m2 != null) {
            addW(m2, false,
                mw(0,1,2,2, 35),
                mw(0,7,1,5, 35),
                mw(2,1,2,4, 20),
                mw(3,4,4,4, 10)
            );
        }

        // ================================================================
        // 马二进三（飞马开局）
        // ================================================================
        Board h1 = play(new Board(), mv(9,1,7,2));
        if (h1 != null) {
            addW(h1, false,
                mw(0,7,1,5, 35),  // 马8进7
                mw(0,1,2,2, 30),  // 马2进3
                mw(2,7,2,4, 20),  // 炮8平5
                mw(2,1,2,4, 15)   // 炮2平5
            );

            Board h1a = play(cp(h1), mv(0,7,1,5));
            if (h1a != null) {
                addW(h1a, true,
                    mw(9,7,7,6, 40),
                    mw(7,1,7,4, 35),
                    mw(6,2,5,2, 25)
                );
            }
        }

        // ================================================================
        // 仙人指路（兵三进一）
        // ================================================================
        Board p1 = play(new Board(), mv(6,2,5,2));
        if (p1 != null) {
            addW(p1, false,
                mw(3,4,4,4, 35),  // 卒5进1（最强反制）
                mw(0,7,1,5, 30),  // 马8进7
                mw(2,7,2,4, 20),  // 炮8平5
                mw(0,1,2,2, 15)   // 马2进3
            );

            Board p1a = play(cp(p1), mv(3,4,4,4));
            if (p1a != null) {
                addW(p1a, true,
                    mw(9,1,7,2, 40),
                    mw(7,7,7,4, 35),
                    mw(9,7,7,6, 25)
                );
            }
        }

        // ================================================================
        // 兵七进一（仙人指路对称变体）
        // ================================================================
        Board p2 = play(new Board(), mv(6,6,5,6));
        if (p2 != null) {
            addW(p2, false,
                mw(3,4,4,4, 35),
                mw(0,1,2,2, 30),
                mw(2,1,2,4, 20),
                mw(0,7,1,5, 15)
            );
        }

        // ================================================================
        // 飞象局（相三进五）
        // ================================================================
        Board e1 = play(new Board(), mv(9,2,7,4));
        if (e1 != null) {
            addW(e1, false,
                mw(0,7,1,5, 35),
                mw(0,1,2,2, 30),
                mw(2,7,2,4, 20),
                mw(9,6,7,4, 15)   // 对飞象
            );

            Board e1a = play(cp(e1), mv(0,7,1,5));
            if (e1a != null) {
                addW(e1a, true,
                    mw(9,7,7,6, 40),
                    mw(7,1,7,4, 35),
                    mw(6,6,5,6, 25)
                );
            }
        }

        // ================================================================
        // 相七进五（飞象局对称）
        // ================================================================
        Board e2 = play(new Board(), mv(9,6,7,4));
        if (e2 != null) {
            addW(e2, false,
                mw(0,1,2,2, 35),
                mw(0,7,1,5, 30),
                mw(2,1,2,4, 20),
                mw(9,2,7,4, 15)
            );
        }

        // ================================================================
        // 反宫马：红马二进三 → 马八进七（不用当头炮）
        // ================================================================
        Board fgm = play(new Board(), mv(9,1,7,2));
        if (fgm != null) {
            Board fgm2 = play(cp(fgm), mv(0,7,1,5));
            if (fgm2 != null) {
                Board fgm3 = play(cp(fgm2), mv(9,7,7,6));
                if (fgm3 != null) {
                    addW(fgm3, false,
                        mw(0,1,2,2, 40),  // 马2进3（屏风马）
                        mw(2,7,2,4, 30),  // 炮8平5
                        mw(0,0,0,2, 20),  // 车9平8
                        mw(3,4,4,4, 10)   // 卒5进1
                    );

                    // 反宫马：红接下来走炮
                    Board fgm4 = play(cp(fgm3), mv(0,1,2,2));
                    if (fgm4 != null) {
                        addW(fgm4, true,
                            mw(7,7,7,4, 40),  // 炮八平五（反宫马中炮）
                            mw(7,1,7,4, 30),  // 炮二平五
                            mw(6,2,5,2, 20),  // 兵三进一
                            mw(6,6,5,6, 10)   // 兵七进一
                        );
                    }
                }
            }
        }

        // ================================================================
        // 过宫炮（炮二平六）
        // ================================================================
        Board ggp = play(new Board(), mv(7,1,7,3));
        if (ggp != null) {
            addW(ggp, false,
                mw(0,7,1,5, 40),
                mw(0,1,2,2, 35),
                mw(2,7,2,4, 25)
            );
        }

        // ================================================================
        // 边炮（炮二平一）
        // ================================================================
        Board bp1 = play(new Board(), mv(7,1,7,0));
        if (bp1 != null) {
            addW(bp1, false,
                mw(0,7,1,5, 40),
                mw(0,1,2,2, 35),
                mw(3,4,4,4, 25)
            );
        }

        // ================================================================
        // 对兵局（双方都走兵）
        // ================================================================
        Board db = play(new Board(), mv(6,4,5,4));  // 兵五进一（中兵）
        if (db != null) {
            addW(db, false,
                mw(3,4,4,4, 45),  // 卒5进1（对兵）
                mw(0,7,1,5, 30),
                mw(0,1,2,2, 25)
            );
        }

        // ================================================================
        // 车八进一（起横车）
        // ================================================================
        Board rp = play(new Board(), mv(9,0,8,0));
        if (rp != null) {
            addW(rp, false,
                mw(0,7,1,5, 35),
                mw(0,1,2,2, 35),
                mw(2,7,2,4, 30)
            );
        }

        // ================================================================
        // 五八炮（炮二平五 → 炮八平五，双炮攻势）
        // 红：炮二平五 → 炮八平五 → 马二进三 → 车一平二 → ...
        // ================================================================
        Board wb1 = play(new Board(), mv(7,1,7,4), mv(0,7,1,5), mv(7,7,7,4));
        if (wb1 != null) {
            addW(wb1, false,
                mw(0,1,2,2, 35),  // 马2进3（屏风马应五八炮）
                mw(3,4,4,4, 30),  // 卒5进1
                mw(2,7,2,5, 20),  // 炮8退3（退炮守中）
                mw(0,0,0,2, 15)   // 车9平8
            );
            Board wb2 = play(cp(wb1), mv(0,1,2,2));
            if (wb2 != null) {
                addW(wb2, true,
                    mw(9,1,7,2, 40),  // 马二进三
                    mw(9,0,8,0, 30),  // 车一进一
                    mw(6,2,5,2, 30)   // 兵三进一
                );
            }
        }

        // ================================================================
        // 顺炮横车（炮二平五 → 炮8平5 → 车一平二 → 车9平8...）
        // ================================================================
        Board sp1 = play(new Board(), mv(7,1,7,4), mv(2,7,2,4));
        if (sp1 != null) {
            // 顺炮：红方接下来的变化
            Board sp2 = play(cp(sp1), mv(9,0,9,1)); // 车一平二
            if (sp2 != null) {
                addW(sp2, false,
                    mw(0,0,0,1, 40),  // 车9平8（顺炮直车对直车）
                    mw(0,1,2,2, 30),  // 马2进3
                    mw(0,7,1,5, 20),  // 马8进7
                    mw(3,4,4,4, 10)   // 卒5进1
                );
                Board sp3 = play(cp(sp2), mv(0,0,0,1));
                if (sp3 != null) {
                    addW(sp3, true,
                        mw(9,1,7,2, 40),   // 马二进三
                        mw(7,7,7,4, 30),   // 炮八平五（鸳鸯炮）
                        mw(6,2,5,2, 20),   // 兵三进一
                        mw(9,7,7,6, 10)    // 马八进七
                    );
                }
            }
        }

        // ================================================================
        // 当头炮 vs 单提马（马8进9）变例
        // ================================================================
        Board dtm_v1 = play(new Board(), mv(7,1,7,4), mv(0,7,2,8));  // 马8进9
        if (dtm_v1 != null) {
            addW(dtm_v1, true,
                mw(9,1,7,2, 45),  // 马二进三
                mw(6,2,5,2, 30),  // 兵三进一
                mw(9,7,7,6, 25)   // 马八进七
            );
        }

        // ================================================================
        // 三步虎（马二进三 → 炮二平五 → 车一平二）
        // ================================================================
        Board sbh = play(new Board(), mv(9,1,7,2), mv(0,7,1,5), mv(7,1,7,4));
        if (sbh != null) {
            addW(sbh, false,
                mw(0,1,2,2, 35),  // 马2进3
                mw(2,7,2,4, 30),  // 炮8平5
                mw(3,4,4,4, 20),  // 卒5进1
                mw(0,0,0,2, 15)   // 车9平8
            );
            Board sbh2 = play(cp(sbh), mv(0,1,2,2), mv(9,0,9,1)); // 车一平二
            if (sbh2 != null) {
                addW(sbh2, false,
                    mw(0,0,0,1, 40),  // 车9平8
                    mw(2,7,1,7, 30),  // 炮8进1
                    mw(3,4,4,4, 20),  // 卒5进1
                    mw(0,8,0,6, 10)   // 车1平3
                );
            }
        }

        // ================================================================
        // 鸳鸯炮（双炮居中：炮二平五 → 炮八平五）强攻变化续
        // 当黑方马2进3 → 马8进7 形成双马对峙时
        // ================================================================
        Board yyp = play(new Board(), mv(7,1,7,4), mv(0,1,2,2),
                         mv(7,7,7,4), mv(0,7,1,5));
        if (yyp != null) {
            addW(yyp, true,
                mw(9,1,7,2, 40),   // 马二进三
                mw(9,7,7,6, 30),   // 马八进七
                mw(9,0,8,0, 20),   // 车一进一
                mw(6,4,5,4, 10)    // 兵五进一（中兵突破）
            );
        }

        // ================================================================
        // 士角炮（炮二平四）
        // ================================================================
        Board sjp = play(new Board(), mv(7,1,7,5));
        if (sjp != null) {
            addW(sjp, false,
                mw(0,7,1,5, 40),
                mw(0,1,2,2, 35),
                mw(3,4,4,4, 25)
            );
        }

        // ================================================================
        // 当头炮 vs 屏风马 深化变例（续接前面的 c3 分支）
        // 红：马八进七 → 兵三进一 → 车一平二 → ...
        // ================================================================
        Board pf1 = play(new Board(),
            mv(7,1,7,4),   // 炮二平五
            mv(0,7,1,5),   // 马8进7
            mv(9,1,7,2),   // 马二进三
            mv(0,1,2,2),   // 马2进3（屏风马）
            mv(9,7,7,6),   // 马八进七
            mv(0,0,0,2),   // 车9平8
            mv(9,0,9,1));  // 车一平二
        if (pf1 != null) {
            addW(pf1, false,
                mw(0,8,0,6, 40),  // 车1平3（互起横车）
                mw(3,4,4,4, 30),  // 卒5进1
                mw(2,7,1,7, 20),  // 炮8进1
                mw(1,5,3,4, 10)   // 马7进6（马踩中卒）
            );
            Board pf2 = play(cp(pf1), mv(0,8,0,6));
            if (pf2 != null) {
                addW(pf2, true,
                    mw(9,1,8,1, 40),  // 车二进一（巡河车）
                    mw(6,2,5,2, 30),  // 兵三进一
                    mw(7,7,7,6, 20),  // 炮八平三（三路炮）
                    mw(9,8,9,7, 10)   // 车九进一
                );
            }
        }

        // ================================================================
        // 马八进七起手（右马盘头）
        // ================================================================
        Board h2 = play(new Board(), mv(9,7,7,6));
        if (h2 != null) {
            addW(h2, false,
                mw(0,7,1,5, 35),  // 马8进7
                mw(0,1,2,2, 30),  // 马2进3
                mw(2,7,2,4, 20),  // 炮8平5
                mw(2,1,2,4, 15)   // 炮2平5
            );
            Board h2a = play(cp(h2), mv(0,7,1,5), mv(9,1,7,2));  // 马8进7 → 马二进三
            if (h2a != null) {
                addW(h2a, false,
                    mw(0,1,2,2, 40),   // 马2进3（双马防御）
                    mw(2,7,2,4, 30),   // 炮8平5
                    mw(0,0,0,2, 20),   // 车9平8
                    mw(3,4,4,4, 10)    // 卒5进1
                );
            }
        }

        // ================================================================
        // 当头炮 → 马8进7 → 马二进三 → 车9平8 变例（车马炮协同）
        // ================================================================
        Board vm1 = play(new Board(),
            mv(7,1,7,4), mv(0,7,1,5), mv(9,1,7,2), mv(0,0,0,1));
        if (vm1 != null) {
            addW(vm1, true,
                mw(9,7,7,6, 40),  // 马八进七
                mw(9,0,9,1, 30),  // 车一平二
                mw(6,2,5,2, 20),  // 兵三进一
                mw(7,7,7,4, 10)   // 炮八平五
            );
        }
    }

    // ---- 工具方法 ----

    /** weight 版走法记录 {fr,fc,tr,tc,weight} */
    private static int[] mw(int fr,int fc,int tr,int tc,int w){
        return new int[]{fr,fc,tr,tc,w};
    }
    /** 无权重版（默认权重10） */
    private static int[] mv(int fr,int fc,int tr,int tc){
        return new int[]{fr,fc,tr,tc,10};
    }

    private static void addW(Board board, boolean isRed, int[]... moves) {
        long hash = zobrist(board, isRed);
        List<int[]> list = BOOK.computeIfAbsent(hash, k -> new ArrayList<>());
        for (int[] m : moves) list.add(m);
    }

    private static Board play(Board board, int[]... moves) {
        for (int[] m : moves) {
            if (board.getPiece(m[0], m[1]) == null) return null;
            board.move(m[0], m[1], m[2], m[3]);
        }
        return board;
    }

    private static Board cp(Board src) {
        Board b = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                b.grid[r][c] = src.grid[r][c] == null ? null : src.grid[r][c].copy();
        return b;
    }
}
