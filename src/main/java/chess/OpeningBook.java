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
     * 1. 尝试在线云库（queryall，按 rank+score 选出最优）
     * 2. 失败/超时则回退本地开局库
     */
    public static LookupResult lookupWithSource(Board board, boolean isRed) {
        // --- 在线查询 ---
        try {
            String fen = board.toFEN(isRed);
            String fenEncoded = URLEncoder.encode(fen, "UTF-8");
            // queryall 返回所有带评分走法，从中选最优 rank 且 score 已知的
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
                    if (mv != null) return new LookupResult(mv, true);
                }
            } else {
                conn.disconnect();
            }
        } catch (Exception ignored) {
            // 网络不可用或超时，静默回退到本地库
        }

        // --- 本地离线开局库兜底 ---
        int[] mv = localLookup(board, isRed);
        return mv != null ? new LookupResult(mv, false) : null;
    }

    /**
     * 从 queryall 返回结果中选最优走法
     * 格式：move:b2e2,score:1,rank:2,note:...,winrate:50.08|move:...
     *
     * 策略：
     *   1. 过滤掉 score=?? 的未知走法
     *   2. 在已知走法中取 rank 最小（最优）
     *   3. rank 相同时随机选一个（增加变化性）
     */
    private static int[] parseBestFromQueryAll(String resp) {
        try {
            String[] entries = resp.trim().split("\\|");
            // {moveStr, rank} 的已知走法列表
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

                if (moveStr != null && scoreKnown &&
                    moveStr.matches("[a-i][0-9][a-i][0-9]") && rank != Integer.MAX_VALUE) {
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
     * UCCI 坐标 → {fromRow, fromCol, toRow, toCol}
     * UCCI: 列 a(0)~i(8)，行 0(黑方底线)~9(红方底线)
     * 我们的坐标系：row=0(黑方),row=9(红方) ——与 UCCI 行号一致
     */
    private static int[] ucciToRowCol(String ucci) {
        int fc = ucci.charAt(0) - 'a';    // from col
        int fr = 9 - (ucci.charAt(1) - '0'); // UCCI行0=红方底=row9
        int tc = ucci.charAt(2) - 'a';
        int tr = 9 - (ucci.charAt(3) - '0');
        if (fc<0||fc>8||fr<0||fr>9||tc<0||tc>8||tr<0||tr>9) return null;
        return new int[]{fr, fc, tr, tc};
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
