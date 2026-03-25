package chess;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * AI 引擎 v3：迭代加深 + Alpha-Beta 剪枝 + 高级优化
 *
 * 核心优化：
 * 1. 置换表 (Transposition Table)          —— 避免重复搜索相同局面，效果最大
 * 2. 杀手启发 (Killer Heuristic)           —— 优先尝试曾产生 Beta 截断的非吃子走法
 * 3. 历史启发 (History Heuristic)          —— 全局统计好走法，改善排序
 * 4. 空着剪枝 (Null Move Pruning)          —— 跳过己方走法测试对手能否截断
 * 5. PVS/Principal Variation Search        —— 非 PV 节点窄窗口搜索
 * 6. 静态搜索 (Quiescence Search)          —— 消除水平效应
 * 7. Lazy SMP 并行搜索                     —— 多线程共享置换表，加速搜索
 * 8. 统计信息 + 走法来源回调               —— 界面实时显示
 */
public class AIEngine {

    // ---- 难度对应时间（毫秒）----
    public static final int TIME_EASY   = 10_000;   // 简单 10秒
    public static final int TIME_MEDIUM = 30_000;   // 中等 30秒
    public static final int TIME_HARD   = 120_000;  // 困难 2分钟

    private static final int INF       = Integer.MAX_VALUE / 2;
    private static final int MAX_DEPTH = 32;

    // ---- Lazy SMP 线程数（最多4线程，自动感知CPU核数）----
    private static final int THREAD_COUNT = Math.min(4, Math.max(1,
            Runtime.getRuntime().availableProcessors() - 1));

    // ---- 置换表（共享，多线程可见）----
    private static final int TT_SIZE   = 1 << 22;  // 4M 槽位（约 128MB）
    private static final int TT_MASK   = TT_SIZE - 1;

    private static final int TT_EXACT  = 0;
    private static final int TT_LOWER  = 1; // Alpha/下界
    private static final int TT_UPPER  = 2; // Beta/上界

    // flat array 置换表，减少GC；volatile 保证跨线程可见
    private final long[]  ttKey   = new long[TT_SIZE];
    private final int[]   ttDepth = new int[TT_SIZE];
    private final int[]   ttScore = new int[TT_SIZE];
    private final byte[]  ttFlag  = new byte[TT_SIZE];
    private final int[]   ttMove  = new int[TT_SIZE]; // 编码 fr*1000+fc*100+tr*10+tc

    // ---- 杀手走法（每个线程独立，按线程索引）----
    private static final int MAX_KILLER_DEPTH = 32;
    // [threadId][ply][4]
    private final int[][][] killer1 = new int[THREAD_COUNT + 1][MAX_KILLER_DEPTH][4];
    private final int[][][] killer2 = new int[THREAD_COUNT + 1][MAX_KILLER_DEPTH][4];

    // ---- 历史启发表（每个线程独立）----
    // [threadId][10][9][10][9]
    private final int[][][][][] histTable = new int[THREAD_COUNT + 1][10][9][10][9];

    // ---- 走法来源枚举 ----
    public enum MoveSource {
        CLOUD_BOOK ("云库"),
        LOCAL_BOOK ("开局库"),
        AI_SEARCH  ("AI搜索");

        public final String label;
        MoveSource(String l) { this.label = l; }
    }

    // ---- 搜索统计 ----
    public static class SearchStats {
        public MoveSource source  = MoveSource.AI_SEARCH;
        public int  depth         = 0;
        public long nodes         = 0;
        public long ttHits        = 0;
        public int  score         = 0;
        public long elapsedMs     = 0;
        public int  threads       = 1;
        /** 当前最优走法 [fr,fc,tr,tc]，null 表示未知 */
        public int[] bestMove     = null;
        /** PV 主线（中文走法序列，换行分隔），每行格式：前缀+中文着法 */
        public String pvLine      = "";
        /**
         * 绝杀步数（站在 AI 视角）：
         *   > 0：AI（redMate=true表示红方）mateIn 步内将死对手
         *   < 0：对手 -mateIn 步内将死 AI
         *   = 0：未发现绝杀（或来自书库）
         */
        public int mateIn         = 0;
        /**
         * 局面评分（站在红方视角，正=红方优，负=黑方优）。
         * 单位：与 Evaluator 评分相同（约 1兵=100分）。
         */
        public int boardScore     = 0;
        /** AI 是否执红（用于界面解读 mateIn 的语义） */
        public boolean aiIsRed    = false;

        public String summary() {
            if (source == MoveSource.AI_SEARCH) {
                return String.format(
                    "AI搜索 | 深度:%d | 节点:%s | TT:%.0f%% | %d线程 | %dms",
                    depth, formatNodes(nodes),
                    nodes > 0 ? ttHits * 100.0 / nodes : 0,
                    threads, elapsedMs);
            }
            return source.label + " | " + elapsedMs + "ms";
        }

        private static String formatNodes(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    public interface StatsListener { void onStats(SearchStats s); }
    private StatsListener statsListener;
    public void setStatsListener(StatsListener l) { this.statsListener = l; }

    // ---- 内部状态（主线程使用）----
    private long timeLimit;
    private long startTime;
    private volatile boolean timeout; // volatile：多线程共享超时信号

    // 主线程最优走法
    private int bestFR, bestFC, bestTR, bestTC;
    private int safeFR, safeFC, safeTR, safeTC;

    // 全局节点计数（AtomicLong 供多线程累计）
    private final AtomicLong totalNodes = new AtomicLong();
    private final AtomicLong totalTtHits = new AtomicLong();

    private final SearchStats stats = new SearchStats();

    /** 当前搜索的棋盘引用（用于提取PV） */
    private Board currentBoard;
    /** 当前AI颜色（用于PV提取） */
    private boolean currentAiIsRed;

    public void setTimeLimit(int ms) { this.timeLimit = ms; }

    // =====================================================================
    // 主入口
    // =====================================================================
    public int[] getBestMove(Board board, boolean aiIsRed) {
        startTime = System.currentTimeMillis();
        timeout   = false;
        safeFR = safeFC = safeTR = safeTC = -1;
        totalNodes.set(0);
        totalTtHits.set(0);
        stats.nodes = 0; stats.ttHits = 0; stats.depth = 0;
        stats.source = MoveSource.AI_SEARCH;
        stats.threads = THREAD_COUNT;
        stats.bestMove = null;
        stats.pvLine = "";
        stats.mateIn = 0;
        stats.aiIsRed = aiIsRed;
        stats.boardScore = Evaluator.evaluate(board); // 初始局面评分
        currentBoard = board;
        currentAiIsRed = aiIsRed;

        // 1. 查开局/云库
        OpeningBook.LookupResult bookResult = OpeningBook.lookupWithSource(board, aiIsRed);
        if (bookResult != null) {
            stats.source = bookResult.fromCloud ? MoveSource.CLOUD_BOOK : MoveSource.LOCAL_BOOK;
            stats.elapsedMs = System.currentTimeMillis() - startTime;
            stats.bestMove = bookResult.move;
            notifyStats();
            return bookResult.move;
        }

        // 2. 清历史/杀手表（置换表跨步保留，复用 TT 价值）
        for (int t = 0; t <= THREAD_COUNT; t++) {
            for (int[][][] a : histTable[t]) for (int[][] b : a) for (int[] c : b) Arrays.fill(c, 0);
            for (int[] k : killer1[t]) Arrays.fill(k, -1);
            for (int[] k : killer2[t]) Arrays.fill(k, -1);
        }

        long rootKey = OpeningBook.zobrist(board, aiIsRed);

        // 3. ★ 快速浅层搜索（1~3层）：提供保底走法，避免"主搜索超时无结果"
        //    独立线程运行，最多花费 min(timeLimit/8, 800ms)，不干扰主搜索
        final int QUICK_DEPTH_MAX = 3;
        final long quickTimeLimit = Math.min(timeLimit / 8, 800);
        final Board quickBoard = copyBoard(board);
        Thread quickThread = new Thread(() -> {
            for (int d = 1; d <= QUICK_DEPTH_MAX && !timeout; d++) {
                int[] bestHolder = {-1,-1,-1,-1};
                int sc = alphaBetaQuick(quickBoard, d, -INF, INF, aiIsRed, 0, rootKey, bestHolder, 0);
                if (!timeout && bestHolder[0] != -1) {
                    int qMate = detectMate(sc, aiIsRed);
                    synchronized (AIEngine.this) {
                        if (safeFR == -1 || qMate > 0) {
                            safeFR = bestHolder[0]; safeFC = bestHolder[1];
                            safeTR = bestHolder[2]; safeTC = bestHolder[3];
                        }
                        if (qMate > 0) {
                            stats.bestMove = new int[]{bestHolder[0],bestHolder[1],bestHolder[2],bestHolder[3]};
                            stats.mateIn = qMate;
                            stats.depth = d;
                            stats.score = sc;
                            stats.elapsedMs = System.currentTimeMillis() - startTime;
                            notifyStats();
                        }
                    }
                    if (qMate > 0) break; // 找到绝杀，浅搜结束
                }
                if (System.currentTimeMillis() - startTime > quickTimeLimit) break;
            }
        }, "quick-search");
        quickThread.setDaemon(true);
        quickThread.start();

        // 4. 启动 Lazy SMP 辅助线程
        ExecutorService pool = null;
        List<Future<?>> futures = new ArrayList<>();
        if (THREAD_COUNT > 1) {
            pool = Executors.newFixedThreadPool(THREAD_COUNT - 1);
            for (int tid = 1; tid < THREAD_COUNT; tid++) {
                final int threadId = tid;
                final Board boardCopy = copyBoard(board);
                final boolean red = aiIsRed;
                futures.add(pool.submit(() ->
                    helperSearch(boardCopy, red, rootKey, threadId)));
            }
        }

        // 5. 主线程迭代加深（threadId=0）
        int lastScore = 0;
        long lastNotifyTime = startTime; // 记录上次通知时间，用于每秒刷新
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            bestFR = bestFC = bestTR = bestTC = -1;
            int sc = alphaBeta(board, depth, -INF, INF, aiIsRed, true, 0, rootKey, false, 0);

            if (!timeout && bestFR != -1) {
                safeFR = bestFR; safeFC = bestFC;
                safeTR = bestTR; safeTC = bestTC;
                lastScore = sc;
                stats.depth = depth;
                stats.score = sc;
                stats.nodes = totalNodes.get();
                stats.ttHits = totalTtHits.get();
                stats.elapsedMs = System.currentTimeMillis() - startTime;
                stats.bestMove = new int[]{safeFR, safeFC, safeTR, safeTC};
                // boardScore 转换为红方视角（正=红优，负=黑优）
                final int MATE_TH = INF - MAX_DEPTH * 2 - 10;
                if (Math.abs(sc) >= MATE_TH) {
                    // 绝杀局面：用固定极值表示
                    stats.boardScore = aiIsRed ? (sc > 0 ? 99999 : -99999)
                                               : (sc < 0 ? 99999 : -99999);
                } else {
                    stats.boardScore = aiIsRed ? sc : -sc;
                }
                // 检测绝杀（根节点分值，站在 aiIsRed 方视角）
                stats.mateIn = detectMate(sc, aiIsRed);
                // 提取PV主线（最多 MAX_DEPTH 步，与搜索深度一致）
                stats.pvLine = extractPV(board, aiIsRed, rootKey, Math.min(depth, MAX_DEPTH));
                // 每深度完成一次通知（同时兼顾每秒通知）
                long now = System.currentTimeMillis();
                if (now - lastNotifyTime >= 800 || depth <= 4) {
                    notifyStats();
                    lastNotifyTime = now;
                }
                // 仅当主搜索深度≥6且确认绝杀，才提前退出（避免浅搜误判）
                if (stats.mateIn > 0 && depth >= 6) {
                    break; // 深层确认绝杀，提前结束
                }
            }
            if (timeout) break;
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > timeLimit * 0.95) break;
        }
        timeout = true; // 通知辅助线程和快速搜索线程停止

        // 5. 等待辅助线程结束
        if (pool != null) {
            pool.shutdownNow();
            try { pool.awaitTermination(200, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) {}
        }

        stats.elapsedMs = System.currentTimeMillis() - startTime;
        stats.nodes = totalNodes.get();
        stats.ttHits = totalTtHits.get();
        if (safeFR != -1) {
            stats.bestMove = new int[]{safeFR, safeFC, safeTR, safeTC};
            stats.mateIn = detectMate(lastScore, aiIsRed);
        }
        notifyStats();

        if (safeFR == -1) return getAnyMove(board, aiIsRed);
        return new int[]{safeFR, safeFC, safeTR, safeTC};
    }

    // =====================================================================
    // 快速浅层搜索（供 quickThread 使用，独立于主搜索）
    // 主要目标：depth=1~5，优先吃将/绝杀走法，确保明显杀招秒出
    // bestHolder[0..3] 输出最优走法坐标
    // =====================================================================
    private int alphaBetaQuick(Board board, int depth, int alpha, int beta,
                               boolean maxing, int ply, long zobristKey,
                               int[] bestHolder, int threadId) {
        if (timeout) return 0;
        totalNodes.incrementAndGet();

        if (depth == 0) {
            return Evaluator.evaluate(board);
        }

        // 生成走法：将吃将走法放在最前面（BFS-like 优先级）
        List<int[]> moves = new ArrayList<>(64);
        List<int[]> captureMoves = new ArrayList<>(16);
        List<int[]> quietMoves   = new ArrayList<>(48);
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = board.getPiece(r, c);
                if (p == null || p.isRed != maxing) continue;
                for (int[] m : board.getLegalMoves(r, c)) {
                    int[] mv = new int[]{r, c, m[0], m[1]};
                    if (board.getPiece(m[0], m[1]) != null) captureMoves.add(mv);
                    else quietMoves.add(mv);
                }
            }
        }
        // 吃将走法（吃将最高优先）+ 其他吃子 + 静默走法
        captureMoves.sort((a, b2) -> {
            Piece ca = board.getPiece(a[2], a[3]);
            Piece cb = board.getPiece(b2[2], b2[3]);
            if (ca != null && ca.type == Piece.Type.KING) return -1;
            if (cb != null && cb.type == Piece.Type.KING) return 1;
            return (cb!=null?pieceValue(cb.type):0) - (ca!=null?pieceValue(ca.type):0);
        });
        moves.addAll(captureMoves);
        moves.addAll(quietMoves);

        if (moves.isEmpty()) {
            // 将死或困毙均为当前方（maxing）输棋
            return maxing ? -(INF - ply) : (INF - ply);
        }

        int best = maxing ? -INF : INF;
        boolean isRoot = (ply == 0);

        for (int[] mv : moves) {
            if (timeout) break;
            Piece cap = board.move(mv[0], mv[1], mv[2], mv[3]);
            long newKey = recomputeZobrist(board, !maxing); // 走后下一步是对方走

            int val;
            if (cap != null && cap.type == Piece.Type.KING) {
                val = maxing ? INF - (ply + 1) : -(INF - (ply + 1));
            } else {
                val = alphaBetaQuick(board, depth - 1, alpha, beta, !maxing, ply + 1, newKey, null, threadId);
            }
            board.undoMove(mv[0], mv[1], mv[2], mv[3], cap);

            if (maxing) {
                if (val > best) {
                    best = val;
                    if (isRoot && bestHolder != null) {
                        bestHolder[0]=mv[0]; bestHolder[1]=mv[1];
                        bestHolder[2]=mv[2]; bestHolder[3]=mv[3];
                    }
                }
                if (val > alpha) alpha = val;
                if (beta <= alpha) break;
            } else {
                if (val < best) {
                    best = val;
                    if (isRoot && bestHolder != null) {
                        bestHolder[0]=mv[0]; bestHolder[1]=mv[1];
                        bestHolder[2]=mv[2]; bestHolder[3]=mv[3];
                    }
                }
                if (val < beta) beta = val;
                if (beta <= alpha) break;
            }
        }
        return best;
    }


    /** 辅助线程：同样迭代加深，但深度从 2 开始，共享置换表 */
    private void helperSearch(Board board, boolean aiIsRed, long rootKey, int threadId) {
        long nodeCount = 0;
        int[][]  lKiller1 = killer1[threadId];
        int[][]  lKiller2 = killer2[threadId];
        int[][][][] lHist = histTable[threadId];

        for (int depth = 2; depth <= MAX_DEPTH && !timeout; depth++) {
            alphaBeta(board, depth, -INF, INF, aiIsRed, false, 0, rootKey, false, threadId);
            if (timeout) break;
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > timeLimit * 0.9) break;
        }
    }

    // =====================================================================
    // Alpha-Beta + PVS + 空着剪枝
    // =====================================================================
    private int alphaBeta(Board board, int depth, int alpha, int beta,
                          boolean maxing, boolean isRoot, int ply,
                          long zobristKey, boolean nullMoveAllowed, int threadId) {
        // 超时检测（每512节点一次）
        long nc = totalNodes.incrementAndGet();
        if ((nc & 0x1FF) == 0) {
            if (System.currentTimeMillis() - startTime >= timeLimit) timeout = true;
        }
        if (timeout) return 0;

        final int MATE_TH = INF - MAX_DEPTH * 2 - 10;

        // ── 置换表查询 ────────────────────────────────────────────────────────
        // 约定（绝对视角，红方正=红优）：
        //   TT_EXACT  → 精确分值
        //   TT_LOWER  → 下界（maxing节点发生beta截断，best >= beta）
        //   TT_UPPER  → 上界（minning节点发生alpha截断，best <= alpha；
        //                     或maxing节点所有走法都 <= originalAlpha）
        // 将死分值归一化：存入时 score -= ply，读出时 score += ply（正值）
        //                 存入时 score += ply，读出时 score -= ply（负值）
        int ttIdx = (int)(zobristKey & TT_MASK);
        if (ttKey[ttIdx] == zobristKey && ttDepth[ttIdx] >= depth) {
            totalTtHits.incrementAndGet();
            int ttSc = this.ttScore[ttIdx];
            // 将死分值反归一化（恢复到当前节点ply对应的绝对分值）
            // 约定：存入时正绝杀 +ply（消除ply偏差），读出时正绝杀 -ply（恢复偏差）
            //       存入时负绝杀 -ply，读出时负绝杀 +ply
            if (ttSc >= MATE_TH)  ttSc -= ply;   // 正绝杀：存入时 +ply，读出 -ply
            else if (ttSc <= -MATE_TH) ttSc += ply; // 负绝杀：存入时 -ply，读出 +ply
            byte flag = this.ttFlag[ttIdx];
            if (!isRoot) {
                if (flag == TT_EXACT)                   return ttSc;
                if (flag == TT_LOWER && ttSc >= beta)   return ttSc; // 下界 ≥ beta → 截断
                if (flag == TT_UPPER && ttSc <= alpha)  return ttSc; // 上界 ≤ alpha → 截断
            }
        }

        if (depth == 0) {
            int score = quiescence(board, alpha, beta, maxing, ply + 1, threadId);
            storeTT(ttIdx, zobristKey, 0, score, TT_EXACT, -1);
            return score;
        }

        // ── 空着剪枝（Null Move Pruning） ─────────────────────────────────────
        // 本引擎为绝对视角（红方正），直接传递alpha/beta，不取反。
        // 空着后棋盘不动，但下一步换对方走，需用对方视角的Zobrist key。
        if (nullMoveAllowed && !isRoot && depth >= 3 && !board.isInCheck(maxing)) {
            int R = depth >= 6 ? 3 : 2;
            // 空着：己方pass，用对方视角key（board状态不变，仅改变轮次标志）
            long nullKey = recomputeZobrist(board, !maxing);
            int nullScore = alphaBeta(board, depth - R - 1, alpha, beta,
                                      !maxing, false, ply + 1, nullKey, false, threadId);
            if (!timeout) {
                if (maxing  && nullScore >= beta)  return beta;   // 即使pass对方也没法改善→截断
                if (!maxing && nullScore <= alpha) return alpha;
            }
        }

        List<int[]> moves = getSortedMoves(board, maxing, ply, ttIdx, zobristKey, threadId);
        if (moves.isEmpty()) {
            // 将死或困毙：当前走方（maxing方）输棋
            return maxing ? -(INF - ply) : (INF - ply);
        }

        int best = maxing ? -INF : INF;
        int lFR = -1, lFC = -1, lTR = -1, lTC = -1;
        int originalAlpha = alpha;
        int originalBeta  = beta;
        boolean firstMove = true;

        for (int[] mv : moves) {
            if (timeout) break;
            Piece cap = board.move(mv[0], mv[1], mv[2], mv[3]);
            long newKey = recomputeZobrist(board, !maxing); // 走后下一步是对方走

            int val;
            if (cap != null && cap.type == Piece.Type.KING) {
                // 吃将：直接赢，ply+1 编码绝杀距离
                val = maxing ? INF - (ply + 1) : -(INF - (ply + 1));
            } else if (firstMove) {
                val = alphaBeta(board, depth - 1, alpha, beta, !maxing, false, ply + 1, newKey, true, threadId);
            } else {
                // PVS 窄窗口搜索（绝对视角minimax框架）
                if (maxing) {
                    // 最大化节点：用 (alpha, alpha+1) 验证是否 > alpha
                    val = alphaBeta(board, depth - 1, alpha, alpha + 1, !maxing, false, ply + 1, newKey, true, threadId);
                    if (!timeout && val > alpha && val < beta) {
                        val = alphaBeta(board, depth - 1, alpha, beta, !maxing, false, ply + 1, newKey, true, threadId);
                    }
                } else {
                    // 最小化节点：用 (beta-1, beta) 验证是否 < beta
                    val = alphaBeta(board, depth - 1, beta - 1, beta, !maxing, false, ply + 1, newKey, true, threadId);
                    if (!timeout && val < beta && val > alpha) {
                        val = alphaBeta(board, depth - 1, alpha, beta, !maxing, false, ply + 1, newKey, true, threadId);
                    }
                }
            }
            board.undoMove(mv[0], mv[1], mv[2], mv[3], cap);
            firstMove = false;

            if (maxing) {
                if (val > best) { best = val; lFR=mv[0]; lFC=mv[1]; lTR=mv[2]; lTC=mv[3]; }
                if (val > alpha) {
                    alpha = val;
                    // 历史启发：只在改善alpha（真正的好走法）时更新
                    histTable[threadId][mv[0]][mv[1]][mv[2]][mv[3]] += depth * depth;
                }
                if (beta <= alpha) {
                    if (cap == null) storeKiller(ply, mv, threadId);
                    break; // beta截断
                }
            } else {
                if (val < best) { best = val; lFR=mv[0]; lFC=mv[1]; lTR=mv[2]; lTC=mv[3]; }
                if (val < beta) {
                    beta = val;
                    // 历史启发：只在改善beta（真正的好走法）时更新
                    histTable[threadId][mv[0]][mv[1]][mv[2]][mv[3]] += depth * depth;
                }
                if (beta <= alpha) {
                    if (cap == null) storeKiller(ply, mv, threadId);
                    break; // alpha截断
                }
            }
        }

        // 主线程更新根走法
        if (isRoot && threadId == 0 && !timeout && lFR != -1) {
            bestFR = lFR; bestFC = lFC; bestTR = lTR; bestTC = lTC;
        }

        if (!timeout) {
            // ── TT flag 判定（绝对视角minimax框架） ─────────────────────────
            // 对 maxing=true（最大化）节点：
            //   best >= beta  → beta截断 → TT_LOWER（下界，至少有这么好）
            //   best <= originalAlpha → 所有走法都不够好 → TT_UPPER（上界）
            //   otherwise → TT_EXACT
            // 对 maxing=false（最小化）节点：
            //   best <= alpha → alpha截断 → TT_LOWER（从红方视角是下界，黑方到此分值≤alpha）
            //                               注意：对最小化节点，alpha截断意味着分值已经足够低，
            //                               其他最大化祖先会选它（不会再向下剪枝）
            //                               实际上 best <= alpha 意味着上界不够好（对红方是上界）
            //   best >= originalBeta → TT_UPPER（对红方是上界不够低，黑方不会选）
            //                          等价：最小化节点的beta截断，说明黑方至少能做到best，是下界
            // 简化：统一按分值范围判断（与maxing无关，基于原始alpha/beta）
            int flag;
            if (best <= originalAlpha) {
                flag = TT_UPPER; // 所有走法都不如alpha，分值是上界
            } else if (best >= originalBeta) {
                flag = TT_LOWER; // 发生截断，分值是下界
            } else {
                flag = TT_EXACT;
            }
            int mv = (lFR >= 0) ? lFR*1000+lFC*100+lTR*10+lTC : -1;
            // 将死分值归一化：存入时消除ply偏移
            // 正绝杀（val = INF - ply，越浅分越高）：存 val + ply = INF（恒定值，不含ply偏差）
            // 负绝杀（val = -(INF - ply)）：存 val - ply = -INF（恒定）
            // 读出时正绝杀 -ply，负绝杀 +ply（恢复到当前节点ply对应的相对距离）
            int storeBest = best;
            if (storeBest >= MATE_TH)  storeBest += ply;  // 正绝杀加ply（归一化）
            else if (storeBest <= -MATE_TH) storeBest -= ply; // 负绝杀减ply（归一化）
            storeTT(ttIdx, zobristKey, depth, storeBest, flag, mv);
        }
        return best;
    }

    // =====================================================================
    // 静态搜索（Quiescence Search）
    // =====================================================================
    private int quiescence(Board board, int alpha, int beta, boolean maxing, int ply, int threadId) {
        if ((totalNodes.incrementAndGet() & 0x1FF) == 0) {
            if (System.currentTimeMillis() - startTime >= timeLimit) timeout = true;
        }
        if (timeout) return 0;

        int standPat = Evaluator.evaluate(board);
        if (maxing) {
            if (standPat >= beta) return beta;
            if (standPat > alpha) alpha = standPat;
        } else {
            if (standPat <= alpha) return alpha;
            if (standPat < beta)  beta = standPat;
        }

        for (int[] mv : getCaptureMoves(board, maxing)) {
            if (timeout) break;
            Piece cap = board.move(mv[0], mv[1], mv[2], mv[3]);
            if (cap != null && cap.type == Piece.Type.KING) {
                board.undoMove(mv[0], mv[1], mv[2], mv[3], cap);
                // quiescence 中吃将，同样用 ply+1 编码
                return maxing ? INF - (ply + 1) : -(INF - (ply + 1));
            }
            long newKey = recomputeZobrist(board, !maxing);
            int val = quiescence(board, alpha, beta, !maxing, ply + 1, threadId);
            board.undoMove(mv[0], mv[1], mv[2], mv[3], cap);
            if (maxing) {
                if (val > alpha) alpha = val;
                if (alpha >= beta) break;
            } else {
                if (val < beta) beta = val;
                if (beta <= alpha) break;
            }
        }
        return maxing ? alpha : beta;
    }

    // =====================================================================
    // 走法生成与排序
    // =====================================================================
    private List<int[]> getSortedMoves(Board board, boolean red, int ply,
                                       int ttIdx, long zobristKey, int threadId) {
        List<int[]> all = new ArrayList<>(64);
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = board.getPiece(r, c);
                if (p == null || p.isRed != red) continue;
                for (int[] m : board.getLegalMoves(r, c))
                    all.add(new int[]{r, c, m[0], m[1]});
            }
        }
        int ttBestMv = (ttKey[ttIdx] == zobristKey) ? ttMove[ttIdx] : -1;
        int[] k1 = killer1[threadId][Math.min(ply, MAX_KILLER_DEPTH-1)];
        int[] k2 = killer2[threadId][Math.min(ply, MAX_KILLER_DEPTH-1)];
        all.sort((a, b2) -> mvPriority(board, b2, ttBestMv, k1, k2, threadId)
                          - mvPriority(board, a,  ttBestMv, k1, k2, threadId));
        return all;
    }

    private int mvPriority(Board board, int[] mv, int ttBestMv, int[] k1, int[] k2, int tid) {
        if (ttBestMv >= 0 && mv[0]*1000+mv[1]*100+mv[2]*10+mv[3] == ttBestMv) return 10_000_000;
        Piece cap = board.getPiece(mv[2], mv[3]);
        if (cap != null) {
            if (cap.type == Piece.Type.KING) return 9_000_000;
            Piece from = board.getPiece(mv[0], mv[1]);
            return 1_000_000 + pieceValue(cap.type)*100
                   - (from != null ? pieceValue(from.type) : 0);
        }
        if (k1[0]==mv[0]&&k1[1]==mv[1]&&k1[2]==mv[2]&&k1[3]==mv[3]) return 900_000;
        if (k2[0]==mv[0]&&k2[1]==mv[1]&&k2[2]==mv[2]&&k2[3]==mv[3]) return 800_000;
        return histTable[tid][mv[0]][mv[1]][mv[2]][mv[3]];
    }

    private List<int[]> getCaptureMoves(Board board, boolean red) {
        List<int[]> caps = new ArrayList<>(16);
        for (int r = 0; r < 10; r++) for (int c = 0; c < 9; c++) {
            Piece p = board.getPiece(r, c);
            if (p == null || p.isRed != red) continue;
            for (int[] m : board.getLegalMoves(r, c))
                if (board.getPiece(m[0], m[1]) != null)
                    caps.add(new int[]{r, c, m[0], m[1]});
        }
        caps.sort((a, b) -> {
            Piece ca = board.getPiece(a[2], a[3]);
            Piece cb = board.getPiece(b[2], b[3]);
            return (cb!=null?pieceValue(cb.type):0) - (ca!=null?pieceValue(ca.type):0);
        });
        return caps;
    }

    // =====================================================================
    // 置换表
    // =====================================================================
    private void storeTT(int idx, long key, int depth, int score, int flag, int mv) {
        if (ttKey[idx] != key || ttDepth[idx] <= depth) {
            ttKey[idx]=key; ttDepth[idx]=depth; ttScore[idx]=score;
            ttFlag[idx]=(byte)flag; ttMove[idx]=mv;
        }
    }

    // =====================================================================
    // 杀手走法
    // =====================================================================
    private void storeKiller(int ply, int[] mv, int tid) {
        if (ply >= MAX_KILLER_DEPTH) return;
        int[] k1 = killer1[tid][ply];
        if (k1[0]==mv[0]&&k1[1]==mv[1]&&k1[2]==mv[2]&&k1[3]==mv[3]) return;
        int[] k2 = killer2[tid][ply];
        k2[0]=k1[0]; k2[1]=k1[1]; k2[2]=k1[2]; k2[3]=k1[3];
        k1[0]=mv[0]; k1[1]=mv[1]; k1[2]=mv[2]; k1[3]=mv[3];
    }

    // =====================================================================
    // Zobrist 重算（走后调用）
    // =====================================================================
    private long recomputeZobrist(Board board, boolean nextIsRed) {
        return OpeningBook.zobrist(board, nextIsRed);
    }

    // =====================================================================
    // Board 深拷贝（辅助线程用）
    // =====================================================================
    private static Board copyBoard(Board src) {
        Board b = new Board();
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                b.grid[r][c] = src.grid[r][c] == null ? null : src.grid[r][c].copy();
        return b;
    }

    // =====================================================================
    // 辅助
    // =====================================================================
    private int pieceValue(Piece.Type t) {
        switch (t) {
            case ROOK:    return 100;
            case CANNON:
            case HORSE:   return 45;
            case ADVISOR:
            case ELEPHANT:return 20;
            case PAWN:    return 10;
            default:      return 0;
        }
    }

    private int[] getAnyMove(Board board, boolean red) {
        for (int r=0;r<10;r++) for (int c=0;c<9;c++) {
            Piece p = board.getPiece(r,c);
            if (p!=null&&p.isRed==red) {
                List<int[]> ms = board.getLegalMoves(r,c);
                if (!ms.isEmpty()) return new int[]{r,c,ms.get(0)[0],ms.get(0)[1]};
            }
        }
        return new int[]{-1,-1,-1,-1};
    }

    private void notifyStats() {
        if (statsListener == null) return;
        SearchStats snap = new SearchStats();
        snap.source=stats.source; snap.depth=stats.depth;
        snap.nodes=stats.nodes; snap.ttHits=stats.ttHits;
        snap.score=stats.score; snap.elapsedMs=stats.elapsedMs;
        snap.threads=THREAD_COUNT;
        snap.bestMove = stats.bestMove != null ? stats.bestMove.clone() : null;
        snap.pvLine = stats.pvLine;
        snap.mateIn = stats.mateIn;
        snap.boardScore = stats.boardScore;
        snap.aiIsRed = stats.aiIsRed;
        statsListener.onStats(snap);
    }

    /**
     * 根据搜索分值判断绝杀步数（站在 AI 视角）。
     *
     * 本引擎使用绝对视角（正=红方优，负=黑方优），因此：
     *   sc >= MATE_TH  → 红方赢（不一定是AI赢！）
     *   sc <= -MATE_TH → 黑方赢
     *
     * 需要结合 aiIsRed 判断是 AI 赢还是对手赢：
     *   aiIsRed=true  && sc >= MATE_TH  → AI(红)赢 → mateIn > 0
     *   aiIsRed=true  && sc <= -MATE_TH → 对手(黑)赢 → mateIn < 0
     *   aiIsRed=false && sc <= -MATE_TH → AI(黑)赢 → mateIn > 0
     *   aiIsRed=false && sc >= MATE_TH  → 对手(红)赢 → mateIn < 0
     *
     * @param sc      alphaBeta 根节点返回的评分（绝对视角，正=红优）
     * @param aiIsRed AI 执红还是执黑
     * @return > 0：AI 将死对手的全步数；< 0：对手将死 AI 的全步数（取负后为步数）；= 0：无绝杀
     */
    private int detectMate(int sc, boolean aiIsRed) {
        final int MATE_TH = INF - MAX_DEPTH * 2 - 10;
        if (sc >= MATE_TH) {
            // 红方赢
            int half = INF - sc;
            int steps = Math.max(1, (half + 1) / 2);
            return aiIsRed ? steps : -steps; // AI执红→AI赢；AI执黑→对手赢
        } else if (sc <= -MATE_TH) {
            // 黑方赢
            int half = INF + sc;
            int steps = Math.max(1, (half + 1) / 2);
            return aiIsRed ? -steps : steps; // AI执红→对手赢；AI执黑→AI赢
        }
        return 0;
    }

    /**
     * 通过置换表追踪 PV 主线（最多 maxSteps 步），返回中文走法序列。
     * 使用 board 的深拷贝，不影响原始棋盘状态。
     */
    private String extractPV(Board board, boolean aiIsRed, long rootKey, int maxSteps) {
        // 使用深拷贝，避免污染原棋盘
        Board b = copyBoard(board);
        StringBuilder sb = new StringBuilder();
        boolean red = aiIsRed;
        long key = rootKey;
        // 防止循环
        java.util.Set<Long> visited = new java.util.HashSet<>();

        for (int step = 0; step < maxSteps; step++) {
            if (visited.contains(key)) break;
            visited.add(key);
            int idx = (int)(key & TT_MASK);
            if (ttKey[idx] != key || ttMove[idx] < 0) break;
            int encoded = ttMove[idx];
            int fr = encoded / 1000;
            int fc = (encoded / 100) % 10;
            int tr = (encoded / 10) % 10;
            int tc = encoded % 10;
            if (!b.inBounds(fr, fc) || !b.inBounds(tr, tc)) break;
            Piece moving = b.getPiece(fr, fc);
            if (moving == null || moving.isRed != red) break;

            // 生成中文走法描述
            String notation = buildMoveNotation(b, fr, fc, tr, tc);
            if (sb.length() > 0) sb.append("\n");
            String prefix = (red == aiIsRed) ? "▶ AI: " : "△ 对手: ";
            sb.append(prefix).append(notation);

            // 执行走法（在副本上）
            Piece cap = b.move(fr, fc, tr, tc);
            if (cap != null && cap.type == Piece.Type.KING) break; // 吃将就停

            key = OpeningBook.zobrist(b, !red);
            red = !red;
        }
        return sb.toString();
    }

    /** 生成单步中文走法描述（不含棋谱编号），供PV显示 */
    private String buildMoveNotation(Board board, int fr, int fc, int tr, int tc) {
        Piece p = board.getPiece(fr, fc);
        if (p == null) return "?";
        String name = p.getDisplay();
        // 列号：红方用中文一~九（从右往左），黑方用1~9（从左往右）
        String fromCol = p.isRed ? chColPV(8 - fc) : String.valueOf(fc + 1);
        String dir;
        if (fr == tr) dir = "平";
        else if (p.isRed) dir = (tr < fr) ? "进" : "退";
        else              dir = (tr > fr) ? "进" : "退";
        String steps;
        if (fr == tr) {
            steps = p.isRed ? chColPV(8 - tc) : String.valueOf(tc + 1);
        } else if (p.type == Piece.Type.HORSE || p.type == Piece.Type.ELEPHANT
                || p.type == Piece.Type.ADVISOR) {
            steps = p.isRed ? chColPV(8 - tc) : String.valueOf(tc + 1);
        } else {
            int dist = Math.abs(tr - fr);
            steps = p.isRed ? chNumPV(dist) : String.valueOf(dist);
        }
        return name + fromCol + dir + steps;
    }

    private static String chColPV(int c) {
        String[] s = {"一","二","三","四","五","六","七","八","九"};
        return (c >= 0 && c < 9) ? s[c] : String.valueOf(c+1);
    }
    private static String chNumPV(int n) {
        String[] s = {"零","一","二","三","四","五","六","七","八","九"};
        return (n >= 0 && n < 10) ? s[n] : String.valueOf(n);
    }
}
