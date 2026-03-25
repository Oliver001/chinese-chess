package chess;

import java.util.ArrayList;
import java.util.List;

public class Board {
    public Piece[][] grid = new Piece[10][9];

    public Board() { initBoard(); }

    public void initBoard() {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) grid[r][c] = null;

        // 黑方
        grid[0][0]=new Piece(Piece.Type.ROOK,false); grid[0][1]=new Piece(Piece.Type.HORSE,false);
        grid[0][2]=new Piece(Piece.Type.ELEPHANT,false); grid[0][3]=new Piece(Piece.Type.ADVISOR,false);
        grid[0][4]=new Piece(Piece.Type.KING,false); grid[0][5]=new Piece(Piece.Type.ADVISOR,false);
        grid[0][6]=new Piece(Piece.Type.ELEPHANT,false); grid[0][7]=new Piece(Piece.Type.HORSE,false);
        grid[0][8]=new Piece(Piece.Type.ROOK,false);
        grid[2][1]=new Piece(Piece.Type.CANNON,false); grid[2][7]=new Piece(Piece.Type.CANNON,false);
        grid[3][0]=new Piece(Piece.Type.PAWN,false); grid[3][2]=new Piece(Piece.Type.PAWN,false);
        grid[3][4]=new Piece(Piece.Type.PAWN,false); grid[3][6]=new Piece(Piece.Type.PAWN,false);
        grid[3][8]=new Piece(Piece.Type.PAWN,false);
        // 红方
        grid[9][0]=new Piece(Piece.Type.ROOK,true); grid[9][1]=new Piece(Piece.Type.HORSE,true);
        grid[9][2]=new Piece(Piece.Type.ELEPHANT,true); grid[9][3]=new Piece(Piece.Type.ADVISOR,true);
        grid[9][4]=new Piece(Piece.Type.KING,true); grid[9][5]=new Piece(Piece.Type.ADVISOR,true);
        grid[9][6]=new Piece(Piece.Type.ELEPHANT,true); grid[9][7]=new Piece(Piece.Type.HORSE,true);
        grid[9][8]=new Piece(Piece.Type.ROOK,true);
        grid[7][1]=new Piece(Piece.Type.CANNON,true); grid[7][7]=new Piece(Piece.Type.CANNON,true);
        grid[6][0]=new Piece(Piece.Type.PAWN,true); grid[6][2]=new Piece(Piece.Type.PAWN,true);
        grid[6][4]=new Piece(Piece.Type.PAWN,true); grid[6][6]=new Piece(Piece.Type.PAWN,true);
        grid[6][8]=new Piece(Piece.Type.PAWN,true);
    }

    public Piece getPiece(int r, int c) {
        return inBounds(r,c) ? grid[r][c] : null;
    }

    public boolean inBounds(int r, int c) {
        return r>=0&&r<10&&c>=0&&c<9;
    }

    public Piece move(int fr, int fc, int tr, int tc) {
        Piece cap = grid[tr][tc];
        grid[tr][tc] = grid[fr][fc];
        grid[fr][fc] = null;
        return cap;
    }

    public void undoMove(int fr, int fc, int tr, int tc, Piece cap) {
        grid[fr][fc] = grid[tr][tc];
        grid[tr][tc] = cap;
    }

    /** 深拷贝棋盘 */
    public Piece[][] copyGrid() {
        Piece[][] g = new Piece[10][9];
        for (int r=0;r<10;r++)
            for (int c=0;c<9;c++)
                g[r][c] = grid[r][c]==null ? null : grid[r][c].copy();
        return g;
    }

    public List<int[]> getLegalMoves(int r, int c) {
        Piece p = grid[r][c];
        if (p==null) return new ArrayList<>();
        List<int[]> raw = getRawMoves(r,c);
        List<int[]> legal = new ArrayList<>();
        for (int[] m : raw) {
            Piece cap = move(r,c,m[0],m[1]);
            if (!isInCheck(p.isRed)) legal.add(m);
            undoMove(r,c,m[0],m[1],cap);
        }
        return legal;
    }

    public List<int[]> getRawMoves(int r, int c) {
        Piece p = grid[r][c];
        if (p==null) return new ArrayList<>();
        switch(p.type){
            case ROOK: return rookMoves(r,c,p.isRed);
            case HORSE: return horseMoves(r,c,p.isRed);
            case ELEPHANT: return elephantMoves(r,c,p.isRed);
            case ADVISOR: return advisorMoves(r,c,p.isRed);
            case KING: return kingMoves(r,c,p.isRed);
            case CANNON: return cannonMoves(r,c,p.isRed);
            case PAWN: return pawnMoves(r,c,p.isRed);
        }
        return new ArrayList<>();
    }

    private List<int[]> rookMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        for(int[]d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int nr=r+d[0],nc=c+d[1];
            while(inBounds(nr,nc)){
                if(grid[nr][nc]==null) m.add(new int[]{nr,nc});
                else{ if(grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc}); break; }
                nr+=d[0];nc+=d[1];
            }
        }
        return m;
    }

    private List<int[]> horseMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        int[][]steps={{-2,-1},{-2,1},{2,-1},{2,1},{-1,-2},{-1,2},{1,-2},{1,2}};
        int[][]legs= {{-1,0},{-1,0},{1,0},{1,0},{0,-1},{0,1},{0,-1},{0,1}};
        for(int i=0;i<8;i++){
            int nr=r+steps[i][0],nc=c+steps[i][1];
            int lr=r+legs[i][0],lc=c+legs[i][1];
            if(inBounds(nr,nc)&&grid[lr][lc]==null)
                if(grid[nr][nc]==null||grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc});
        }
        return m;
    }

    private List<int[]> elephantMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        int[][]s={{-2,-2},{-2,2},{2,-2},{2,2}},e={{-1,-1},{-1,1},{1,-1},{1,1}};
        for(int i=0;i<4;i++){
            int nr=r+s[i][0],nc=c+s[i][1];
            if(!inBounds(nr,nc)) continue;
            if(red&&nr<5) continue; if(!red&&nr>4) continue;
            if(grid[r+e[i][0]][c+e[i][1]]!=null) continue;
            if(grid[nr][nc]==null||grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc});
        }
        return m;
    }

    private List<int[]> advisorMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        for(int[]s:new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}}){
            int nr=r+s[0],nc=c+s[1];
            if(!inBounds(nr,nc)||nc<3||nc>5) continue;
            if(red&&nr<7) continue; if(!red&&nr>2) continue;
            if(grid[nr][nc]==null||grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc});
        }
        return m;
    }

    private List<int[]> kingMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        for(int[]s:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
            int nr=r+s[0],nc=c+s[1];
            if(!inBounds(nr,nc)||nc<3||nc>5) continue;
            if(red&&nr<7) continue; if(!red&&nr>2) continue;
            if(grid[nr][nc]==null||grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc});
        }
        return m;
    }

    private List<int[]> cannonMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        for(int[]d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int nr=r+d[0],nc=c+d[1]; boolean platform=false;
            while(inBounds(nr,nc)){
                if(!platform){
                    if(grid[nr][nc]==null) m.add(new int[]{nr,nc});
                    else platform=true;
                } else {
                    if(grid[nr][nc]!=null){ if(grid[nr][nc].isRed!=red) m.add(new int[]{nr,nc}); break; }
                }
                nr+=d[0];nc+=d[1];
            }
        }
        return m;
    }

    private List<int[]> pawnMoves(int r,int c,boolean red){
        List<int[]> m=new ArrayList<>();
        if(red){
            int nr=r-1;
            if(inBounds(nr,c)&&(grid[nr][c]==null||!grid[nr][c].isRed)) m.add(new int[]{nr,c});
            if(r<5){
                if(inBounds(r,c-1)&&(grid[r][c-1]==null||!grid[r][c-1].isRed)) m.add(new int[]{r,c-1});
                if(inBounds(r,c+1)&&(grid[r][c+1]==null||!grid[r][c+1].isRed)) m.add(new int[]{r,c+1});
            }
        } else {
            int nr=r+1;
            if(inBounds(nr,c)&&(grid[nr][c]==null||grid[nr][c].isRed)) m.add(new int[]{nr,c});
            if(r>=5){
                if(inBounds(r,c-1)&&(grid[r][c-1]==null||grid[r][c-1].isRed)) m.add(new int[]{r,c-1});
                if(inBounds(r,c+1)&&(grid[r][c+1]==null||grid[r][c+1].isRed)) m.add(new int[]{r,c+1});
            }
        }
        return m;
    }

    public boolean isInCheck(boolean red){
        int kr=-1,kc=-1;
        for(int r=0;r<10;r++) for(int c=0;c<9;c++)
            if(grid[r][c]!=null&&grid[r][c].type==Piece.Type.KING&&grid[r][c].isRed==red){kr=r;kc=c;}
        if(kr==-1) return true;
        for(int r=0;r<10;r++) for(int c=0;c<9;c++){
            Piece p=grid[r][c];
            if(p!=null&&p.isRed!=red)
                for(int[]mv:getRawMoves(r,c)) if(mv[0]==kr&&mv[1]==kc) return true;
        }
        // 飞将
        for(int r=0;r<10;r++){
            if(r==kr) continue;
            if(grid[r][kc]!=null&&grid[r][kc].type==Piece.Type.KING&&grid[r][kc].isRed!=red){
                boolean blocked=false;
                for(int i=Math.min(kr,r)+1;i<Math.max(kr,r);i++) if(grid[i][kc]!=null){blocked=true;break;}
                if(!blocked) return true;
            }
        }
        return false;
    }

    /**
     * 判断格子(tr,tc)是否被颜色为 attackerIsRed 的棋子攻击（不考虑王面对面飞将）。
     * 用于 Evaluator 中计算 hanging piece 惩罚。
     */
    public boolean isAttackedBy(int tr, int tc, boolean attackerIsRed) {
        for (int r = 0; r < 10; r++) {
            Piece p = grid[r][0]; // inner loop below
            for (int c = 0; c < 9; c++) {
                p = grid[r][c];
                if (p == null || p.isRed != attackerIsRed) continue;
                for (int[] mv : getRawMoves(r, c))
                    if (mv[0] == tr && mv[1] == tc) return true;
            }
        }
        return false;
    }

    /**
     * 统计格子(tr,tc)被 attackerIsRed 颜色攻击的棋子数量和最小攻击者价值。
     * 返回 int[2]：[攻击者数, 最小价值攻击者的基础价值（对应 Evaluator 量级：车1000 马400 炮450...）]
     */
    public int[] getAttackInfo(int tr, int tc, boolean attackerIsRed) {
        int count = 0, minVal = Integer.MAX_VALUE;
        int[] valMap = {0, 10000, 1000, 450, 400, 200, 200, 100}; // KING,ROOK,CANNON,HORSE,ELEPHANT,ADVISOR,PAWN
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p == null || p.isRed != attackerIsRed) continue;
                for (int[] mv : getRawMoves(r, c)) {
                    if (mv[0] == tr && mv[1] == tc) {
                        count++;
                        int v = pieceBaseVal(p.type);
                        if (v < minVal) minVal = v;
                        break;
                    }
                }
            }
        }
        return new int[]{count, minVal == Integer.MAX_VALUE ? 0 : minVal};
    }

    private static int pieceBaseVal(Piece.Type t) {
        switch (t) {
            case ROOK:     return 1000;
            case CANNON:   return 450;
            case HORSE:    return 400;
            case ADVISOR:
            case ELEPHANT: return 200;
            case PAWN:     return 100;
            default:       return 10000;
        }
    }

    public boolean hasLegalMoves(boolean red){
        for(int r=0;r<10;r++) for(int c=0;c<9;c++)
            if(grid[r][c]!=null&&grid[r][c].isRed==red&&!getLegalMoves(r,c).isEmpty()) return true;
        return false;
    }

    /**
     * 生成皮卡鱼/UCI-Cyclone标准FEN字符串。
     * 皮卡鱼FEN规则（来自源码注释）：
     *   "Each rank is described, starting with rank 9 and ending with rank 0."
     *   rank0 = 红方底线(WHITE) = 内部 row9
     *   rank9 = 黑方底线(BLACK) = 内部 row0
     * 因此 FEN 段顺序从内部 row0 到 row9（即 rank9 降到 rank0）。
     * 走棋方：w=红方(WHITE), b=黑方(BLACK)。
     */
    public String toFEN(boolean redTurn) {
        StringBuilder sb = new StringBuilder();
        // FEN从 rank9(内部row0) 到 rank0(内部row9)，即内部 r=0..9 顺序输出
        for (int r = 0; r < 10; r++) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p == null) { empty++; }
                else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(pieceToFENChar(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (r < 9) sb.append('/');
        }
        // UCI标准：w=红方(WHITE), b=黑方(BLACK)
        sb.append(redTurn ? " w" : " b");
        return sb.toString();
    }

    /**
     * 生成 chessdb.cn UCCI 标准 FEN。
     * chessdb FEN 棋子段与 toFEN 相同（row0到row9），但走棋方标记相反：
     *   b=红方（row9大写那侧），w=黑方（row0小写那侧）
     */
    public String toUCCIFEN(boolean redTurn) {
        StringBuilder sb = new StringBuilder();
        // 棋子段与toFEN相同（内部 r=0..9 顺序输出）
        for (int r = 0; r < 10; r++) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p == null) { empty++; }
                else {
                    if (empty > 0) { sb.append(empty); empty = 0; }
                    sb.append(pieceToFENChar(p));
                }
            }
            if (empty > 0) sb.append(empty);
            if (r < 9) sb.append('/');
        }
        // chessdb UCCI：b=红方，w=黑方（与UCI/皮卡鱼相反）
        sb.append(redTurn ? " b" : " w");
        return sb.toString();
    }

    private char pieceToFENChar(Piece p) {
        char c;
        switch(p.type){
            // 皮卡鱼/UCI-Cyclone标准：马=n(knight) 象=b(bishop) 士=a 车=r 炮=c 将/帅=k 兵/卒=p
            case KING:     c = 'k'; break;
            case ADVISOR:  c = 'a'; break;
            case ELEPHANT: c = 'b'; break;  // bishop，不是e
            case HORSE:    c = 'n'; break;  // knight，不是h
            case ROOK:     c = 'r'; break;
            case CANNON:   c = 'c'; break;
            default:       c = 'p'; break;
        }
        return p.isRed ? Character.toUpperCase(c) : c;
    }

    /**
     * 从皮卡鱼/UCI-Cyclone标准FEN恢复棋盘。
     * FEN段0=rank9=内部row0（黑方底线），段9=rank0=内部row9（红方底线）。
     * 直接用 FEN段index 作为内部row，无需翻转。
     */
    public boolean fromFEN(String fen) {
        try {
            String[] parts = fen.trim().split("\\s+");
            String[] rows = parts[0].split("/");
            for(int r=0;r<10;r++) for(int c=0;c<9;c++) grid[r][c]=null;
            // FEN段index直接对应内部row（段0=内部row0=黑方底线=rank9）
            for (int r = 0; r < Math.min(10, rows.length); r++) {
                int c = 0;
                for (char ch : rows[r].toCharArray()) {
                    if (c >= 9) break;
                    if (Character.isDigit(ch)) { c += ch-'0'; }
                    else {
                        boolean red = Character.isUpperCase(ch);
                        Piece.Type t;
                        switch(Character.toLowerCase(ch)){
                            case 'k': t=Piece.Type.KING;     break;
                            case 'a': t=Piece.Type.ADVISOR;  break;
                            case 'b': t=Piece.Type.ELEPHANT; break; // UCI标准: bishop=象
                            case 'e': t=Piece.Type.ELEPHANT; break; // 旧格式兼容
                            case 'n': t=Piece.Type.HORSE;    break; // UCI标准: knight=马
                            case 'h': t=Piece.Type.HORSE;    break; // 旧格式兼容
                            case 'r': t=Piece.Type.ROOK;     break;
                            case 'c': t=Piece.Type.CANNON;   break;
                            default:  t=Piece.Type.PAWN;
                        }
                        grid[r][c++] = new Piece(t, red);
                    }
                }
            }
            // 兼容 UCI 标准的 "w"（红/白方）和旧格式 "r"
            return parts.length > 1 && (parts[1].equals("r") || parts[1].equals("w"));
        } catch(Exception e){ return true; }
    }
}
