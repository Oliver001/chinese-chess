package chess;

public class Piece {
    public enum Type { KING, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN }

    public final Type type;
    public final boolean isRed;

    public Piece(Type type, boolean isRed) {
        this.type = type;
        this.isRed = isRed;
    }

    public String getDisplay() {
        if (isRed) {
            switch (type) {
                case KING: return "帅"; case ADVISOR: return "仕";
                case ELEPHANT: return "相"; case HORSE: return "马";
                case ROOK: return "车"; case CANNON: return "炮"; case PAWN: return "兵";
            }
        } else {
            switch (type) {
                case KING: return "将"; case ADVISOR: return "士";
                case ELEPHANT: return "象"; case HORSE: return "马";
                case ROOK: return "车"; case CANNON: return "炮"; case PAWN: return "卒";
            }
        }
        return "?";
    }

    public Piece copy() { return new Piece(type, isRed); }
}
