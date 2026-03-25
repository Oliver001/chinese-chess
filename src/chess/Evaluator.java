package chess;

public class Evaluator {
    private static final int VAL_KING=10000,VAL_ROOK=1000,VAL_CANNON=450,
        VAL_HORSE=400,VAL_ELEPHANT=200,VAL_ADVISOR=200,VAL_PAWN=100;

    private static final int[][] ROOK_POS={
        {14,14,12,18,16,18,12,14,14},{16,20,18,24,26,24,18,20,16},
        {12,12,12,18,18,18,12,12,12},{12,18,16,22,22,22,16,18,12},
        {12,14,12,18,18,18,12,14,12},{12,16,14,20,20,20,14,16,12},
        {10,14,12,18,18,18,12,14,10},{10,14,12,18,20,18,12,14,10},
        {12,12,12,16,18,16,12,12,12},{ 4, 8, 4,16,12,16, 4, 8, 4}};
    private static final int[][] HORSE_POS={
        { 4, 8,16,12, 4,12,16, 8, 4},{ 4,10,28,16, 8,16,28,10, 4},
        {12,14,16,20,18,20,16,14,12},{ 8,24,18,24,20,24,18,24, 8},
        { 6,16,14,18,16,18,14,16, 6},{ 4,12,16,14,12,14,16,12, 4},
        { 4,10,12,14,10,14,12,10, 4},{ 4, 6,10, 8, 6, 8,10, 6, 4},
        { 0, 4, 6, 6, 0, 6, 6, 4, 0},{ 0, 2, 4, 4, 0, 4, 4, 2, 0}};
    private static final int[][] CANNON_POS={
        { 6, 4, 0,-10,-12,-10, 0, 4, 6},{ 2, 2, 0, -4,-14, -4, 0, 2, 2},
        { 2, 6, 4,  0,-10,  0, 4, 6, 2},{ 0, 0, 0,  2,  8,  2, 0, 0, 0},
        { 0, 0, 0,  2,  8,  2, 0, 0, 0},{-2, 0, 4,  2,  6,  2, 4, 0,-2},
        { 0, 0, 0,  2,  6,  2, 0, 0, 0},{ 4, 2, 4,  6,  6,  6, 4, 2, 4},
        { 2, 2, 2,  6,  6,  6, 2, 2, 2},{ 0, 0, 2,  6,  6,  6, 2, 0, 0}};
    private static final int[][] PAWN_POS={
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 4, 0, 4, 0,14, 0, 4, 0, 4},
        { 4, 0, 4, 0,14, 0, 4, 0, 4},{14,18,22,35,40,35,22,18,14},
        {20,28,34,40,44,40,34,28,20},{24,32,38,44,48,44,38,32,24},
        {26,34,42,44,46,44,42,34,26},{28,36,42,44,46,44,42,36,28}};
    private static final int[][] ELEPHANT_POS={
        { 0, 0,-2, 0, 0, 0,-2, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 4, 0, 0, 0, 4, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 4, 0, 6, 0, 4, 0, 0},{ 2, 0, 0, 0, 8, 0, 0, 0, 2},
        { 0, 0, 4, 0, 6, 0, 4, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 4, 0, 0, 0, 4, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0}};
    private static final int[][] ADVISOR_POS={
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 4, 0, 0, 0, 0, 0, 4, 0},
        { 0, 0, 0, 4, 0, 4, 0, 0, 0},{ 0, 4, 0, 0, 0, 0, 0, 4, 0}};
    private static final int[][] KING_POS={
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0, 0, 0, 0, 0, 0, 0},
        { 0, 0, 0, 0, 0, 0, 0, 0, 0},{ 0, 0, 0,-8,-8,-8, 0, 0, 0},
        { 0, 0, 0,-8,-12,-8, 0, 0, 0},{ 0, 0, 0, 4, 0, 4, 0, 0, 0}};

    public static int evaluate(Board board){
        int score=0;
        for(int r=0;r<10;r++) for(int c=0;c<9;c++){
            Piece p=board.getPiece(r,c);
            if(p==null) continue;
            int v=baseVal(p.type)+posVal(p,r,c);
            score += p.isRed ? v : -v;
        }
        // ── Hanging piece 惩罚：被对方攻击且未被己方保护的棋子扣分 ──────────────
        // 只惩罚车、马、炮（高价值棋子送子问题最多），象/仕/卒不计（价值低，容忍度高）
        score += hangingPenalty(board, true)   // 红方悬空子 → 负值（对红不利）
               + hangingPenalty(board, false);  // 黑方悬空子 → 正值（对红有利）
        return score;
    }

    /**
     * 计算 isRed 方棋子中悬空棋子（被对方攻击且未被己方保护）的代价。
     * 返回值：从红方视角计算（isRed=true 返回负值惩罚红，isRed=false 返回正值惩罚黑）。
     * 惩罚约为 min(攻击者价值, 被吃子价值) * 系数（仅估计，不做完整SEE）。
     */
    private static int hangingPenalty(Board board, boolean isRed) {
        int penalty = 0;
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = board.getPiece(r, c);
                if (p == null || p.isRed != isRed) continue;
                // 只关注高价值棋子
                if (p.type == Piece.Type.KING || p.type == Piece.Type.PAWN
                        || p.type == Piece.Type.ADVISOR || p.type == Piece.Type.ELEPHANT) continue;
                // 一次扫描同时统计攻击者和保护者
                int atkCount = 0, minAtkVal = Integer.MAX_VALUE;
                boolean defended = false;
                for (int rr = 0; rr < 10; rr++) {
                    for (int cc = 0; cc < 9; cc++) {
                        Piece q = board.getPiece(rr, cc);
                        if (q == null) continue;
                        if (rr == r && cc == c) continue; // 跳过自身
                        for (int[] mv : board.getRawMoves(rr, cc)) {
                            if (mv[0] == r && mv[1] == c) {
                                if (q.isRed != isRed) { // 对方攻击者
                                    atkCount++;
                                    int av = baseVal(q.type);
                                    if (av < minAtkVal) minAtkVal = av;
                                } else { // 己方保护者
                                    defended = true;
                                }
                                break;
                            }
                        }
                        if (atkCount > 0 && defended) break; // 已足够信息可提前退出内层
                    }
                    if (atkCount > 0 && defended) break;
                }
                if (atkCount == 0) continue; // 未被攻击，跳过
                int myVal = baseVal(p.type);
                if (!defended) {
                    // 无保护，完全悬空：惩罚80%的棋子价值
                    penalty += myVal * 4 / 5;
                } else if (minAtkVal < myVal) {
                    // 有保护但攻击者价值更低，以小换大风险，惩罚50%净亏损
                    penalty += (myVal - minAtkVal) / 2;
                }
            }
        }
        return isRed ? -penalty : penalty;
    }

    private static int baseVal(Piece.Type t){
        switch(t){case KING:return VAL_KING;case ROOK:return VAL_ROOK;
            case CANNON:return VAL_CANNON;case HORSE:return VAL_HORSE;
            case ELEPHANT:return VAL_ELEPHANT;case ADVISOR:return VAL_ADVISOR;default:return VAL_PAWN;}
    }

    private static int posVal(Piece p,int row,int col){
        int r=p.isRed?row:(9-row);
        switch(p.type){
            case ROOK:return ROOK_POS[r][col]; case HORSE:return HORSE_POS[r][col];
            case CANNON:return CANNON_POS[r][col]; case PAWN:return PAWN_POS[r][col];
            case ELEPHANT:return ELEPHANT_POS[r][col]; case ADVISOR:return ADVISOR_POS[r][col];
            case KING:return KING_POS[r][col];
        }
        return 0;
    }
}
