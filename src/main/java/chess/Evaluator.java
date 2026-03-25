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
        return score;
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
