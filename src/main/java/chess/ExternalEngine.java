package chess;

import java.io.*;
import java.util.function.Consumer;

/**
 * 外部象棋引擎适配器。
 * 通过子进程通信支持 UCI（皮卡鱼等）和 UCCI（象棋巫师等）协议引擎。
 *
 * UCI-Cyclone坐标 ↔ 内部坐标（完全相同，无需翻转）：
 *   行：0=黑方底线（棋盘顶部），9=红方底线（棋盘底部）—— 与内部坐标一致
 *   列：a-i 对应内部列 0-8 —— 与内部坐标一致
 *   示例：c3c4 → fromRow=3,fromCol=2, toRow=4,toCol=2
 */
public class ExternalEngine {

    public enum Protocol { UCI, UCCI, AUTO }

    /** 开启后将所有引擎I/O打印到 System.err，便于调试 */
    public static boolean DEBUG = true;

    private final String enginePath;
    private Protocol protocol;

    private Process process;
    private PrintWriter engineIn;   // GUI→引擎
    private BufferedReader engineOut; // 引擎→GUI

    /** 收到 bestmove 时的回调 */
    private volatile Consumer<int[]> moveCallback;
    /** 收到分析信息(info)时的回调 */
    private volatile Consumer<String> infoCallback;

    private volatile boolean ready = false;
    private Thread readerThread;

    public ExternalEngine(String enginePath, Protocol protocol) {
        this.enginePath = enginePath;
        this.protocol   = protocol;
    }

    public String getEnginePath() { return enginePath; }

    /**
     * 启动引擎进程并完成握手（阻塞，超时约8秒）。
     * 成功返回 true，失败返回 false。
     */
    public boolean start() throws IOException {
        log(">>> 启动引擎: " + enginePath);
        ProcessBuilder pb = new ProcessBuilder(enginePath);
        pb.redirectErrorStream(true);
        process = pb.start();

        engineIn  = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream())), true); // autoFlush=true
        engineOut = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

        // ─── 握手阶段（同步读取，尚未启动readerThread）───
        boolean handshakeOk = false;

        if (protocol == Protocol.UCI || protocol == Protocol.AUTO) {
            send("uci");
            if (waitForKeyword("uciok", 6000)) {
                protocol = Protocol.UCI;
                handshakeOk = true;
                log("<<< UCI握手成功");
            } else if (protocol == Protocol.AUTO) {
                send("ucci");
                if (waitForKeyword("ucciok", 4000)) {
                    protocol = Protocol.UCCI;
                    handshakeOk = true;
                    log("<<< UCCI握手成功");
                }
            }
        } else { // UCCI
            send("ucci");
            if (waitForKeyword("ucciok", 6000)) {
                protocol = Protocol.UCCI;
                handshakeOk = true;
                log("<<< UCCI握手成功");
            }
        }

        if (!handshakeOk) {
            log("!!! 握手失败");
            stop(); return false;
        }

        send("isready");
        if (!waitForKeyword("readyok", 8000)) {
            log("!!! isready 超时");
            stop(); return false;
        }
        log("<<< readyok 确认");

        // ─── 握手完成，启动异步读取线程 ───
        readerThread = new Thread(this::readLoop, "engine-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        ready = true;
        log(">>> 引擎就绪: " + getName() + " 协议=" + protocol);
        return true;
    }

    /** 停止引擎进程 */
    public void stop() {
        ready = false;
        try { send("quit"); } catch (Exception ignored) {}
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        log(">>> 引擎已停止");
    }

    /**
     * 请求引擎搜索最优走法（异步，结果通过回调返回）。
     */
    public void requestMove(Board board, boolean redTurn, int timeLimitMs,
                            Consumer<int[]> onMove, Consumer<String> onInfo) {
        if (!ready) {
            log("!!! requestMove 但引擎未就绪");
            onMove.accept(null);
            return;
        }
        this.moveCallback = onMove;
        this.infoCallback = onInfo;

        String fen = board.toFEN(redTurn);
        log(">>> position fen " + fen);
        send("position fen " + fen);

        String goCmd = "go movetime " + timeLimitMs;
        log(">>> " + goCmd);
        send(goCmd);
    }

    /** 中断当前搜索 */
    public void stopSearch() {
        log(">>> stop");
        send("stop");
    }

    // ──────────────────────────────────────────────
    // 内部
    // ──────────────────────────────────────────────

    private void send(String cmd) {
        if (engineIn == null) return;
        engineIn.println(cmd);
        // PrintWriter(autoFlush=true) 每次println自动flush
    }

    /**
     * 同步等待引擎输出中出现包含 keyword 的行（握手阶段用）。
     * 使用阻塞 readLine()，比 stdout.ready() 更可靠。
     */
    private boolean waitForKeyword(String keyword, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            // 用独立线程做超时读取
            final String[] found = {null};
            Thread t = new Thread(() -> {
                try {
                    String line;
                    while ((line = engineOut.readLine()) != null) {
                        log("<<< [握手] " + line);
                        if (line.contains(keyword)) {
                            found[0] = line;
                            return;
                        }
                    }
                } catch (IOException ignored) {}
            });
            t.setDaemon(true);
            t.start();
            long remaining = deadline - System.currentTimeMillis();
            t.join(Math.max(remaining, 100));
            return found[0] != null;
        } catch (Exception e) {
            log("!!! waitForKeyword 异常: " + e.getMessage());
            return false;
        }
    }

    /** 异步读取线程（握手后运行） */
    private void readLoop() {
        try {
            String line;
            while ((line = engineOut.readLine()) != null) {
                final String l = line.trim();
                log("<<< " + l);
                if (l.startsWith("info") && infoCallback != null) {
                    try { infoCallback.accept(l); } catch (Exception e) {
                        log("!!! infoCallback 异常: " + e.getMessage());
                    }
                }
                if (l.startsWith("bestmove")) {
                    handleBestMove(l);
                }
            }
            log("<<< 引擎输出流结束");
        } catch (IOException e) {
            log("!!! readLoop IO异常: " + e.getMessage());
        }
    }

    /** 解析 bestmove 行并回调 */
    private void handleBestMove(String line) {
        // 格式: bestmove e2e4  或  bestmove e2e4 ponder d7d5  或  bestmove (none)
        log("<<< bestmove行: " + line);
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) {
            log("!!! bestmove 格式错误: " + line);
            if (moveCallback != null) moveCallback.accept(null);
            return;
        }
        String mv = parts[1]; // 只取第一个走法，忽略ponder
        log("<<< 解析到走法: " + mv);

        if (mv.equals("(none)") || mv.equals("0000") || mv.equals("null")) {
            log("<<< 无合法走法");
            if (moveCallback != null) moveCallback.accept(null);
            return;
        }
        int[] internal = iccsToInternal(mv);
        if (internal == null) {
            log("!!! 坐标解析失败: " + mv);
            if (moveCallback != null) moveCallback.accept(null);
            return;
        }
        log(String.format("<<< 坐标转换: %s → [%d,%d]→[%d,%d]",
                mv, internal[0], internal[1], internal[2], internal[3]));
        if (moveCallback != null) moveCallback.accept(internal);
    }

    // ──────────────────────────────────────────────
    // 坐标转换（静态工具方法）
    // ──────────────────────────────────────────────

    /**
     * UCI-Cyclone坐标字符串 → 内部坐标 [fromRow, fromCol, toRow, toCol]
     *
     * 皮卡鱼坐标系（来自源码注释）：
     *   rank0 = 红方底线(WHITE) = 内部 row9
     *   rank9 = 黑方底线(BLACK) = 内部 row0
     * 转换：internal_row = 9 - pikafish_rank
     *
     * 示例：c3c4 → pikafish rank3→rank4 → 内部 [6,2]→[5,2]（红兵向前推进）
     */
    public static int[] iccsToInternal(String mv) {
        if (mv == null || mv.length() < 4) return null;
        try {
            int fromCol      = mv.charAt(0) - 'a';
            int pikafishFrom = mv.charAt(1) - '0';
            int toCol        = mv.charAt(2) - 'a';
            int pikafishTo   = mv.charAt(3) - '0';
            // 范围校验
            if (fromCol < 0 || fromCol > 8 || pikafishFrom < 0 || pikafishFrom > 9) return null;
            if (toCol   < 0 || toCol   > 8 || pikafishTo   < 0 || pikafishTo   > 9) return null;
            int fromRow = 9 - pikafishFrom;
            int toRow   = 9 - pikafishTo;
            return new int[]{fromRow, fromCol, toRow, toCol};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 内部坐标 → UCI-Cyclone坐标字符串
     * pikafish_rank = 9 - internal_row
     * 示例：内部 [6,2]→[5,2] → c3c4
     */
    public static String internalToIccs(int fr, int fc, int tr, int tc) {
        return "" + (char)('a' + fc) + (char)('0' + (9 - fr))
                  + (char)('a' + tc) + (char)('0' + (9 - tr));
    }

    public boolean isReady()      { return ready; }
    public Protocol getProtocol() { return protocol; }

    public String getName() {
        java.io.File f = new java.io.File(enginePath);
        String n = f.getName();
        if (n.endsWith(".exe") || n.endsWith(".app"))
            n = n.substring(0, n.lastIndexOf('.'));
        return n;
    }

    private static void log(String msg) {
        if (DEBUG) System.err.println("[Engine] " + msg);
    }
}
