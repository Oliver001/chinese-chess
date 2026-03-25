package chess;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 外部象棋引擎适配器。
 * 通过子进程通信支持 UCI（皮卡鱼等）和 UCCI（象棋巫师等）协议引擎。
 *
 * UCI/UCCI 协议流程：
 *   GUI → 引擎: uci (或 ucci)
 *   引擎 → GUI: uciok (或 ucciok)
 *   GUI → 引擎: isready
 *   引擎 → GUI: readyok
 *   GUI → 引擎: position fen <FEN>
 *   GUI → 引擎: go movetime <毫秒>
 *   引擎 → GUI: bestmove <ICCS着法>
 *
 * UCI-Cyclone坐标 ↔ 内部坐标（完全相同，无需翻转）：
 *   行：0=黑方底线（棋盘顶部），9=红方底线（棋盘底部）—— 与内部坐标一致
 *   列：a-i 对应内部列 0-8 —— 与内部坐标一致
 *   示例：c3c4 → fromRow=3,fromCol=2, toRow=4,toCol=2
 */
public class ExternalEngine {

    public enum Protocol { UCI, UCCI, AUTO }

    private final String enginePath;
    private Protocol protocol;

    private Process process;
    private PrintWriter stdin;
    private BufferedReader stdout;

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
     * 启动引擎进程并完成握手（阻塞，超时约6秒）。
     * 成功返回 true，失败返回 false。
     */
    public boolean start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(enginePath);
        pb.redirectErrorStream(true);
        process = pb.start();

        stdin  = new PrintWriter(new BufferedWriter(
                 new OutputStreamWriter(process.getOutputStream())));
        stdout = new BufferedReader(
                 new InputStreamReader(process.getInputStream()));

        // ─── 握手阶段（同步读取，尚未启动readerThread）───
        boolean handshakeOk = false;

        if (protocol == Protocol.AUTO || protocol == Protocol.UCI) {
            send("uci");
            if (waitForKeyword("uciok", 4000)) {
                protocol = Protocol.UCI;
                handshakeOk = true;
            } else if (protocol == Protocol.AUTO) {
                send("ucci");
                if (waitForKeyword("ucciok", 3000)) {
                    protocol = Protocol.UCCI;
                    handshakeOk = true;
                }
            }
        } else { // UCCI
            send("ucci");
            if (waitForKeyword("ucciok", 4000)) {
                protocol = Protocol.UCCI;
                handshakeOk = true;
            }
        }

        if (!handshakeOk) { stop(); return false; }

        send("isready");
        if (!waitForKeyword("readyok", 5000)) { stop(); return false; }

        // ─── 握手完成，启动异步读取线程 ───
        readerThread = new Thread(this::readLoop, "engine-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        ready = true;
        return true;
    }

    /** 停止引擎进程 */
    public void stop() {
        ready = false;
        try { send("quit"); } catch (Exception ignored) {}
        if (process != null) { process.destroyForcibly(); process = null; }
    }

    /**
     * 请求引擎搜索最优走法（异步，结果通过回调返回）。
     *
     * @param board       当前棋盘（内部坐标系）
     * @param redTurn     是否红方走
     * @param timeLimitMs 思考时间（毫秒）
     * @param onMove      bestmove 回调（内部坐标 int[]{fr,fc,tr,tc}，失败返回 null）
     * @param onInfo      每次收到 info 行的回调（可为 null）
     */
    public void requestMove(Board board, boolean redTurn, int timeLimitMs,
                            Consumer<int[]> onMove, Consumer<String> onInfo) {
        if (!ready) { onMove.accept(null); return; }
        this.moveCallback = onMove;
        this.infoCallback = onInfo;
        String fen = board.toFEN(redTurn);
        send("position fen " + fen);
        send("go movetime " + timeLimitMs);
    }

    /** 中断当前搜索 */
    public void stopSearch() { send("stop"); }

    // ──────────────────────────────────────────────
    // 内部
    // ──────────────────────────────────────────────

    private void send(String cmd) {
        if (stdin == null) return;
        stdin.println(cmd);
        stdin.flush();
    }

    /**
     * 同步等待引擎输出中出现包含 keyword 的行（握手阶段用）。
     */
    private boolean waitForKeyword(String keyword, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (stdout.ready()) {
                    String line = stdout.readLine();
                    if (line != null && line.contains(keyword)) return true;
                } else {
                    Thread.sleep(20);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 异步读取线程（握手后运行） */
    private void readLoop() {
        try {
            String line;
            while ((line = stdout.readLine()) != null) {
                final String l = line.trim();
                if (l.startsWith("info") && infoCallback != null) {
                    infoCallback.accept(l);
                }
                if (l.startsWith("bestmove")) {
                    handleBestMove(l);
                }
            }
        } catch (IOException ignored) {}
    }

    /** 解析 bestmove 行并回调 */
    private void handleBestMove(String line) {
        // 格式: bestmove e2e4  或  bestmove (none)
        String[] parts = line.split("\\s+");
        if (parts.length < 2) return;
        String mv = parts[1];
        if (mv.equals("(none)") || mv.equals("0000") || mv.equals("null")) {
            if (moveCallback != null) moveCallback.accept(null);
            return;
        }
        int[] internal = iccsToInternal(mv);
        if (moveCallback != null) moveCallback.accept(internal);
    }

    // ──────────────────────────────────────────────
    // 坐标转换（静态工具方法）
    // ──────────────────────────────────────────────

    /**
     * UCI-Cyclone坐标字符串 → 内部坐标 [fromRow, fromCol, toRow, toCol]
     * 皮卡鱼行号与内部坐标完全一致（行0=黑方底线，行9=红方底线），无需翻转。
     * 示例：c3c4 → [3, 2, 4, 2]
     */
    public static int[] iccsToInternal(String mv) {
        if (mv == null || mv.length() < 4) return null;
        try {
            int fromCol = mv.charAt(0) - 'a';
            int fromRow = mv.charAt(1) - '0';   // 直接使用，无需翻转
            int toCol   = mv.charAt(2) - 'a';
            int toRow   = mv.charAt(3) - '0';   // 直接使用，无需翻转
            return new int[]{fromRow, fromCol, toRow, toCol};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 内部坐标 → UCI-Cyclone坐标字符串
     * 行号直接映射，无需翻转。
     * 示例：[3, 2, 4, 2] → c3c4
     */
    public static String internalToIccs(int fr, int fc, int tr, int tc) {
        return "" + (char)('a' + fc) + (char)('0' + fr)
                  + (char)('a' + tc) + (char)('0' + tr);
    }

    public boolean isReady()         { return ready; }
    public Protocol getProtocol()    { return protocol; }
    public String getName() {
        java.io.File f = new java.io.File(enginePath);
        String n = f.getName();
        // 去掉常见扩展名
        if (n.endsWith(".exe") || n.endsWith(".app")) n = n.substring(0, n.lastIndexOf('.'));
        return n;
    }
}
