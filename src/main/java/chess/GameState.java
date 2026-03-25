package chess;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 游戏状态管理：悔棋栈、棋谱、计时器、时制、对局保存
 */
public class GameState {

    // =====================================================================
    // 难度
    // =====================================================================
    public enum Difficulty {
        EASY(AIEngine.TIME_EASY,   "简单"),
        MEDIUM(AIEngine.TIME_MEDIUM,"中等"),
        HARD(AIEngine.TIME_HARD,   "困难");

        public final int aiTimeMs;
        public final String label;
        Difficulty(int t, String l) { this.aiTimeMs = t; this.label = l; }
    }

    // =====================================================================
    // 时制（参考中国象棋竞赛规则2020版）
    // =====================================================================
    public enum TimeControl {
        /**
         * 加秒制（全国联赛标准）：每方基础用时 + 每步加秒
         * 例：全国甲级联赛：20分钟 + 5秒/步；
         *     慢棋正规赛：  60分钟 + 30秒/步
         */
        INCREMENTAL("加秒制", -1, -1, -1) {
            @Override public String describe() {
                return String.format("加秒制：基础%d分钟，每步加%d秒", baseMinutes, incSeconds);
            }
        },
        /**
         * 限时包干制：用完即负，不加秒
         * 例：快棋包干：5分钟 / 10分钟 / 15分钟
         */
        FIXED("包干制", -1, 0, -1) {
            @Override public String describe() {
                return String.format("包干制：每方%d分钟", baseMinutes);
            }
        },
        /**
         * 无限制（休闲模式）
         */
        UNLIMITED("无限制", 999, 0, 0) {
            @Override public String describe() { return "无限制（休闲模式）"; }
        };

        public final String label;
        public int baseMinutes; // 基础分钟
        public int incSeconds;  // 每步加秒（加秒制用）
        public int byoyomiSec;  // 秒读（预留，暂不实现）

        TimeControl(String l, int base, int inc, int byo) {
            this.label = l; this.baseMinutes = base;
            this.incSeconds = inc; this.byoyomiSec = byo;
        }
        public abstract String describe();
    }

    // 预设时制方案（仿照常见赛事）
    public static final Object[][] TIME_PRESETS = {
        // { 显示名, TimeControl类型, 基础分钟, 加秒 }
        { "全国联赛标准（20分+5秒）",   TimeControl.INCREMENTAL, 20,  5  },
        { "正规慢棋（60分+30秒）",       TimeControl.INCREMENTAL, 60,  30 },
        { "业余赛事（45分+30秒）",       TimeControl.INCREMENTAL, 45,  30 },
        { "网络快棋（10分+3秒）",        TimeControl.INCREMENTAL, 10,  3  },
        { "超快棋（5分+3秒）",           TimeControl.INCREMENTAL, 5,   3  },
        { "包干快棋（15分钟）",          TimeControl.FIXED,       15,  0  },
        { "包干快棋（10分钟）",          TimeControl.FIXED,       10,  0  },
        { "包干快棋（5分钟）",           TimeControl.FIXED,       5,   0  },
        { "无限制（休闲）",              TimeControl.UNLIMITED,   999, 0  },
    };

    // =====================================================================
    // 走法记录（含评分，用于折线图）
    // =====================================================================
    public static class MoveRecord {
        public final int fr, fc, tr, tc;
        public final Piece captured;
        public final Piece[][] gridSnapshot;
        public final String notation;
        public final boolean wasRedTurn;
        public final int redTimeLeft, blackTimeLeft;
        /** 走棋后的局面评分（红方视角，正=红优） */
        public int boardScore = 0;

        public MoveRecord(int fr, int fc, int tr, int tc, Piece cap,
                          Piece[][] snap, String nota, boolean wasRed,
                          int redT, int blackT) {
            this.fr=fr; this.fc=fc; this.tr=tr; this.tc=tc;
            this.captured=cap; this.gridSnapshot=snap;
            this.notation=nota; this.wasRedTurn=wasRed;
            this.redTimeLeft=redT; this.blackTimeLeft=blackT;
        }
    }

    // =====================================================================
    // 状态字段
    // =====================================================================
    public final Board board = new Board();
    public boolean redTurn  = true;
    public boolean gameOver = false;
    public boolean humanIsRed = true;
    public Difficulty difficulty = Difficulty.MEDIUM;

    // 时制
    public TimeControl timeControl = TimeControl.INCREMENTAL;
    public int tcBaseMinutes = 20;
    public int tcIncSeconds  = 5;

    public final Deque<MoveRecord> history   = new ArrayDeque<>();
    public final List<String>      notations = new ArrayList<>();

    public int redTimeLeft   = tcBaseMinutes * 60;
    public int blackTimeLeft = tcBaseMinutes * 60;

    // 对局元信息
    public String gameId   = "";
    public String gameDate = "";

    // =====================================================================
    // 初始化 / 重置
    // =====================================================================
    public void reset() {
        board.initBoard();
        redTurn=true; gameOver=false;
        history.clear(); notations.clear();
        redTimeLeft  = tcBaseMinutes * 60;
        blackTimeLeft = tcBaseMinutes * 60;
        gameId   = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        gameDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    // =====================================================================
    // 走棋
    // =====================================================================
    public Piece doMove(int fr, int fc, int tr, int tc) {
        Piece[][] snap = board.copyGrid();
        Piece cap = board.move(fr, fc, tr, tc);
        String nota = buildNotation(fr, fc, tr, tc, snap);
        MoveRecord rec = new MoveRecord(fr,fc,tr,tc,cap,snap,nota,redTurn,redTimeLeft,blackTimeLeft);
        rec.boardScore = Evaluator.evaluate(board);
        history.push(rec);
        // 棋谱格式：红方先走，奇数步=红，偶数步=黑，无需标色（规则已知）
        notations.add(nota);
        redTurn = !redTurn;
        return cap;
    }

    public boolean undoOne() {
        if (history.isEmpty()) return false;
        MoveRecord rec = history.pop();
        for (int r=0;r<10;r++) System.arraycopy(rec.gridSnapshot[r],0,board.grid[r],0,9);
        redTurn = rec.wasRedTurn;
        if (!notations.isEmpty()) notations.remove(notations.size()-1);
        redTimeLeft=rec.redTimeLeft; blackTimeLeft=rec.blackTimeLeft;
        gameOver=false;
        return true;
    }

    public boolean undoTwoSteps() {
        boolean ok = undoOne();
        if (ok) undoOne();
        return ok;
    }

    // =====================================================================
    // 计时
    // =====================================================================
    /**
     * 每秒调用一次。返回 false 表示当前方超时。
     * 走棋后调用 applyIncrement() 加秒。
     */
    public boolean tick() {
        if (gameOver) return true;
        if (redTurn) { if(--redTimeLeft<=0){redTimeLeft=0;return false;} }
        else         { if(--blackTimeLeft<=0){blackTimeLeft=0;return false;} }
        return true;
    }

    /** 走棋完成后加秒（加秒制）*/
    public void applyIncrement(boolean wasRed) {
        if (timeControl == TimeControl.INCREMENTAL && tcIncSeconds > 0) {
            if (wasRed) redTimeLeft   += tcIncSeconds;
            else        blackTimeLeft += tcIncSeconds;
        }
    }

    public String formatTime(int sec) {
        if (sec >= 3600) return String.format("%d:%02d:%02d", sec/3600, (sec%3600)/60, sec%60);
        return String.format("%02d:%02d", sec/60, sec%60);
    }

    // =====================================================================
    // 对局保存 / 加载（简单文本格式，兼容性好）
    // =====================================================================
    /** 保存目录 */
    public static Path getSaveDir() {
        String home = System.getProperty("user.home");
        return Paths.get(home, ".chess_games");
    }

    /**
     * 保存当前对局为 .cgame 文件（自定义文本格式，VERSION=2）。
     * 格式：每行一个字段，"KEY=VALUE"。
     * 对于进行中的对局（result=null/"进行中"），额外保存当前FEN+走棋方，支持"继续对局"。
     */
    public void saveGame(String result) {
        try {
            Path dir = getSaveDir();
            Files.createDirectories(dir);
            String fname = gameId + ".cgame";
            Path file = dir.resolve(fname);

            boolean inProgress = (result == null || result.equals("进行中"));
            if (inProgress) result = "进行中";

            StringBuilder sb = new StringBuilder();
            sb.append("VERSION=2\n");
            sb.append("DATE=").append(gameDate).append("\n");
            sb.append("HUMAN_IS_RED=").append(humanIsRed).append("\n");
            sb.append("DIFFICULTY=").append(difficulty.name()).append("\n");
            sb.append("TIME_CONTROL=").append(timeControl.name()).append("\n");
            sb.append("TC_BASE_MIN=").append(tcBaseMinutes).append("\n");
            sb.append("TC_INC_SEC=").append(tcIncSeconds).append("\n");
            sb.append("RESULT=").append(result).append("\n");
            sb.append("IN_PROGRESS=").append(inProgress).append("\n");
            // 保存当前FEN（含走棋方），用于"继续对局"
            sb.append("CURRENT_FEN=").append(board.toFEN(redTurn)).append("\n");
            sb.append("RED_TURN=").append(redTurn).append("\n");
            sb.append("RED_TIME=").append(redTimeLeft).append("\n");
            sb.append("BLACK_TIME=").append(blackTimeLeft).append("\n");
            sb.append("MOVES=");

            // 走法序列（从旧到新）
            List<MoveRecord> recs = new ArrayList<>(history);
            Collections.reverse(recs);
            for (MoveRecord r : recs) {
                // 格式：fr,fc,tr,tc,score;
                sb.append(r.fr).append(",").append(r.fc).append(",")
                  .append(r.tr).append(",").append(r.tc).append(",")
                  .append(r.boardScore).append(";");
            }
            sb.append("\n");

            // 棋谱文本
            sb.append("NOTATIONS=");
            for (String n : notations) sb.append(n).append("|");
            sb.append("\n");

            Files.writeString(file, sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 对局记录（用于加载列表和继续对局） */
    public static class GameRecord {
        public final Path file;
        public final String date, result, difficulty, timeDesc;
        public final boolean humanIsRed;
        public final List<int[]> moves;      // [fr,fc,tr,tc]
        public final List<Integer> scores;   // 每步评分
        public final List<String> notations;
        // --- 继续对局所需字段 ---
        public final boolean inProgress;     // true=对局进行中，可继续
        public final String currentFen;      // 当前局面FEN（不含走棋方，单独用redTurn）
        public final boolean redTurn;        // 当前走棋方
        public final int redTimeLeft;        // 红方剩余时间（秒），-1=未知
        public final int blackTimeLeft;      // 黑方剩余时间（秒），-1=未知
        public final String difficultyName;  // 难度枚举名

        public GameRecord(Path f, String d, String r, String diff,
                          String td, boolean hir,
                          List<int[]> mv, List<Integer> sc, List<String> no,
                          boolean ip, String fen, boolean rt, int rTime, int bTime) {
            this.file=f; this.date=d; this.result=r; this.difficulty=diff;
            this.timeDesc=td; this.humanIsRed=hir;
            this.moves=mv; this.scores=sc; this.notations=no;
            this.inProgress=ip; this.currentFen=fen; this.redTurn=rt;
            this.redTimeLeft=rTime; this.blackTimeLeft=bTime;
            this.difficultyName=diff;
        }

        @Override public String toString() {
            String status = inProgress ? "【进行中】" : "";
            return date + "  " + (humanIsRed?"执红":"执黑") + "  " + result + status;
        }
    }

    /** 加载所有历史对局 */
    public static List<GameRecord> loadAllGames() {
        List<GameRecord> list = new ArrayList<>();
        try {
            Path dir = getSaveDir();
            if (!Files.exists(dir)) return list;
            Files.list(dir)
                .filter(p -> p.toString().endsWith(".cgame"))
                .sorted(Comparator.reverseOrder()) // 最新在前
                .forEach(f -> {
                    GameRecord r = loadGame(f);
                    if (r != null) list.add(r);
                });
        } catch (Exception ignored) {}
        return list;
    }

    /** 解析单个 .cgame 文件 */
    public static GameRecord loadGame(Path file) {
        try {
            Map<String,String> kv = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq > 0) kv.put(line.substring(0,eq), line.substring(eq+1));
            }
            String date   = kv.getOrDefault("DATE","未知");
            String result = kv.getOrDefault("RESULT","未知");
            String diff   = kv.getOrDefault("DIFFICULTY","MEDIUM");
            String tcName = kv.getOrDefault("TIME_CONTROL","UNLIMITED");
            int base = Integer.parseInt(kv.getOrDefault("TC_BASE_MIN","20"));
            int inc  = Integer.parseInt(kv.getOrDefault("TC_INC_SEC","0"));
            boolean hir = Boolean.parseBoolean(kv.getOrDefault("HUMAN_IS_RED","true"));

            String timeDesc = tcName.equals("INCREMENTAL")
                ? base + "分+" + inc + "秒"
                : tcName.equals("FIXED") ? base + "分包干" : "无限制";

            List<int[]>  moves  = new ArrayList<>();
            List<Integer> scores = new ArrayList<>();
            String movesStr = kv.getOrDefault("MOVES","");
            for (String seg : movesStr.split(";")) {
                if (seg.trim().isEmpty()) continue;
                String[] p = seg.split(",");
                if (p.length >= 4) {
                    moves.add(new int[]{
                        Integer.parseInt(p[0].trim()),
                        Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()),
                        Integer.parseInt(p[3].trim())
                    });
                    scores.add(p.length >= 5 ? Integer.parseInt(p[4].trim()) : 0);
                }
            }

            List<String> notas = new ArrayList<>();
            String notaStr = kv.getOrDefault("NOTATIONS","");
            for (String n : notaStr.split("\\|")) {
                if (!n.trim().isEmpty()) notas.add(n.trim());
            }

            // VERSION=2 新增字段
            boolean inProgress = Boolean.parseBoolean(kv.getOrDefault("IN_PROGRESS","false"));
            String currentFen  = kv.getOrDefault("CURRENT_FEN","");
            boolean redTurn    = Boolean.parseBoolean(kv.getOrDefault("RED_TURN","true"));
            int redTime   = Integer.parseInt(kv.getOrDefault("RED_TIME","-1"));
            int blackTime = Integer.parseInt(kv.getOrDefault("BLACK_TIME","-1"));

            return new GameRecord(file, date, result, diff, timeDesc, hir,
                                  moves, scores, notas,
                                  inProgress, currentFen, redTurn, redTime, blackTime);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // =====================================================================
    // 中文棋谱
    // =====================================================================
    private String buildNotation(int fr, int fc, int tr, int tc, Piece[][] snap) {
        return buildNotationStatic(fr, fc, tr, tc, snap, redTurn);
    }

    /**
     * 静态版本，供外部（如外部引擎PV解析）调用。
     * isRed: 走棋方是否为红方（决定列号和进退方向）。
     */
    public static String buildNotationStatic(int fr, int fc, int tr, int tc, Piece[][] snap, boolean isRed) {
        if (snap == null || fr < 0 || fr > 9 || fc < 0 || fc > 8) return "?";
        Piece p = snap[fr][fc];
        if (p == null) return "?";
        String name = p.getDisplay();
        String fromCol = p.isRed ? chColStatic(8-fc) : String.valueOf(fc+1);
        String dir = (fr==tr) ? "平" : (p.isRed ? (tr<fr?"进":"退") : (tr>fr?"进":"退"));
        String steps;
        if (fr == tr) {
            steps = p.isRed ? chColStatic(8-tc) : String.valueOf(tc+1);
        } else if (p.type==Piece.Type.HORSE || p.type==Piece.Type.ELEPHANT ||
                   p.type==Piece.Type.ADVISOR) {
            steps = p.isRed ? chColStatic(8-tc) : String.valueOf(tc+1);
        } else {
            steps = p.isRed ? chNumStatic(Math.abs(tr-fr)) : String.valueOf(Math.abs(tr-fr));
        }
        return name+fromCol+dir+steps;
    }

    private static String chColStatic(int c) {
        return new String[]{"一","二","三","四","五","六","七","八","九"}[Math.max(0,Math.min(8,c))];
    }
    private static String chNumStatic(int n) {
        return n>=0&&n<=9 ? new String[]{"零","一","二","三","四","五","六","七","八","九"}[n] : String.valueOf(n);
    }
}

