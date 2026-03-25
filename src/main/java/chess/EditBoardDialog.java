package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.BiConsumer;

/**
 * 编辑棋局对话框（拖拽 + 点选版）：
 *  - 右侧：棋子库面板（按棋盘翻转状态决定红/黑方顺序）
 *  - 中央：棋盘（支持从棋子库拖入/点选放置、棋盘内拖动、拖出棋盘删除）
 *  - 规则：每种棋子有数量上限，棋子只能放在合法区域
 *
 * 棋子数量上限（象棋规则）：
 *   帅/将：各1；仕/士：各2；相/象：各2；
 *   马/馬：各2；车/車：各2；炮/砲：各2；兵/卒：各5
 *
 * 位置限制：
 *   帅/将：只能在己方九宫（3×3）
 *   仕/士：只能在己方九宫
 *   相/象：只能在己方半边（不过河）
 *   兵/卒：不能放在己方卒林线以内（己方腹地）
 *           坐标：行0=黑方底线，行9=红方底线，行5=红方卒林线，行4=黑方卒林线
 *           红兵可放行0-5（对方区域+卒林线），不能放行6-9（己方腹地）
 *           黑卒可放行4-9（对方区域+卒林线），不能放行0-3（己方腹地）
 *   马/车/炮：全盘任意位置
 *
 * 交互方式：
 *   1. 拖拽：从棋子库拖拽到棋盘；棋盘内拖动；拖出棋盘外删除
 *   2. 点选：点棋子库选中（高亮），再点棋盘格放置；已选中时点棋盘棋子=换选；点选后右键/ESC取消
 */
public class EditBoardDialog extends JDialog {

    private static final int CELL    = 56;
    private static final int MARGIN  = 36;
    private static final int PIECE_R = 22;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;

    // 每种棋子数量上限
    private static final int MAX_KING     = 1;
    private static final int MAX_ADVISOR  = 2;
    private static final int MAX_ELEPHANT = 2;
    private static final int MAX_HORSE    = 2;
    private static final int MAX_ROOK     = 2;
    private static final int MAX_CANNON   = 2;
    private static final int MAX_PAWN     = 5;

    // ---- 编辑棋盘 ----
    private final Board editBoard = new Board();
    private boolean editRedTurn = true;
    /** 棋盘是否翻转（联动主界面翻转状态：翻转=红方在下） */
    private final boolean boardFlipped;

    // ---- 拖拽状态 ----
    private Piece dragPiece = null;
    private int dragFromRow = -1, dragFromCol = -1;
    private int dragX = -1, dragY = -1;
    private boolean dragFromBoard = false;

    // ---- 点选状态 ----
    /** 当前通过点选选中的棋子（来自棋子库），null=未选中 */
    private Piece clickSelectedPiece = null;
    /** 点选高亮的棋盘格（来自棋盘，即选中棋盘上的棋子准备移动），-1=未选 */
    private int clickFromRow = -1, clickFromCol = -1;

    // ---- UI ----
    private JPanel boardPanel;
    private JPanel palettePanelRed;
    private JPanel palettePanelBlack;
    private JPanel paletteContainer;  // 包含红黑两方，顺序按翻转状态
    private JLabel hintLabel;
    private JLabel[] redCountLabels;
    private JLabel[] blackCountLabels;
    /** 当前被点选高亮的棋子库按钮 */
    private JButton clickSelectedBtn = null;

    private static final Piece.Type[] PIECE_TYPES = {
        Piece.Type.KING, Piece.Type.ADVISOR, Piece.Type.ELEPHANT,
        Piece.Type.HORSE, Piece.Type.ROOK, Piece.Type.CANNON, Piece.Type.PAWN
    };

    private final BiConsumer<Board, Boolean> onConfirm;
    private final Runnable onCancel;

    public EditBoardDialog(Component parent, Board currentBoard, boolean currentRedTurn,
                           boolean boardFlipped,
                           BiConsumer<Board, Boolean> onConfirm, Runnable onCancel) {
        super(SwingUtilities.getWindowAncestor(parent), "摆谱 / 编辑棋局",
              ModalityType.APPLICATION_MODAL);
        this.onConfirm   = onConfirm;
        this.onCancel    = onCancel;
        this.boardFlipped = boardFlipped;

        // 复制当前棋盘
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                editBoard.grid[r][c] = currentBoard.grid[r][c] != null
                        ? currentBoard.grid[r][c].copy() : null;
        editRedTurn = currentRedTurn;

        setLayout(new BorderLayout(4, 4));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { onCancel.run(); }
        });

        add(createBoardPanel(), BorderLayout.CENTER);
        add(createPalettePanel(), BorderLayout.EAST);
        add(createBottomPanel(), BorderLayout.SOUTH);

        // ESC 取消点选
        getRootPane().registerKeyboardAction(e -> cancelClickSelect(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setResizable(true);
        setMinimumSize(new Dimension(660, 560));
        setLocationRelativeTo(parent);
    }

    // =========================================================
    // ── 棋盘面板（拖拽 + 点选）
    // =========================================================
    private JPanel createBoardPanel() {
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawPieces(g2);
                drawSelectionHighlights(g2);
                // 拖拽时绘制浮动棋子 + 目标格高亮
                if (dragPiece != null && dragX >= 0) {
                    int[] snap = snapToGrid(dragX, dragY);
                    if (snap != null) {
                        boolean ok = canPlace(dragPiece, snap[0], snap[1]);
                        g2.setColor(ok ? new Color(0, 200, 0, 80) : new Color(220, 0, 0, 80));
                        int sx = snapScreenX(snap[1]), sy = snapScreenY(snap[0]);
                        g2.fillOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
                        g2.setColor(ok ? new Color(0, 200, 0, 180) : new Color(220, 0, 0, 180));
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
                    }
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
                    drawPieceAt(g2, dragPiece, dragX, dragY, PIECE_R + 2);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                }
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    cancelClickSelect();
                    return;
                }
                int[] rc = pixelToGrid(e.getX(), e.getY());

                // ─── 有点选棋子（来自棋子库）→ 放置到棋盘 ───
                if (clickSelectedPiece != null) {
                    if (rc != null && canPlace(clickSelectedPiece, rc[0], rc[1])) {
                        editBoard.grid[rc[0]][rc[1]] = clickSelectedPiece.copy();
                        updatePaletteCountLabels();
                        setHint("已放置 " + (clickSelectedPiece.isRed?"红":"黑") + clickSelectedPiece.getDisplay()
                                + " → 继续点棋子库选择，或右键/ESC取消");
                    } else if (rc != null) {
                        setHint("⚠ 该格不符合放置规则，请选择其他格子");
                    }
                    // 点选保持，让用户可以连续放同类棋子；右键/ESC取消
                    boardPanel.repaint();
                    return;
                }

                // ─── 无点选 → 开始从棋盘拖拽 ───
                if (rc == null) return;
                Piece p = editBoard.grid[rc[0]][rc[1]];
                if (p == null) return;
                dragPiece = p;
                dragFromRow = rc[0]; dragFromCol = rc[1];
                dragFromBoard = true;
                dragX = e.getX(); dragY = e.getY();
                editBoard.grid[rc[0]][rc[1]] = null;
                updatePaletteCountLabels();
                boardPanel.repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragPiece == null) return;
                dragX = e.getX(); dragY = e.getY();
                boardPanel.repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragPiece == null) return;
                int[] snap = snapToGrid(e.getX(), e.getY());
                if (snap != null && canPlace(dragPiece, snap[0], snap[1])) {
                    editBoard.grid[snap[0]][snap[1]] = dragPiece;
                } else if (dragFromBoard) {
                    // 拖回原格或非法格 → 原格还原
                    if (snap != null && snap[0]==dragFromRow && snap[1]==dragFromCol) {
                        editBoard.grid[dragFromRow][dragFromCol] = dragPiece;
                    }
                    // 拖到棋盘外 = 删除（不恢复）
                }
                dragPiece = null; dragX = -1; dragY = -1;
                dragFromRow = -1; dragFromCol = -1;
                updatePaletteCountLabels();
                boardPanel.repaint();
            }
        };
        boardPanel.addMouseListener(ma);
        boardPanel.addMouseMotionListener(ma);
        return boardPanel;
    }

    /** 取消点选状态 */
    private void cancelClickSelect() {
        clickSelectedPiece = null;
        clickFromRow = -1; clickFromCol = -1;
        if (clickSelectedBtn != null) {
            clickSelectedBtn.repaint();
            clickSelectedBtn = null;
        }
        setHint("从右侧棋子库拖拽或点选棋子到棋盘；棋盘内可拖动；拖出棋盘外删除");
        boardPanel.repaint();
    }

    /** 绘制点选高亮 */
    private void drawSelectionHighlights(Graphics2D g) {
        // 棋盘上有选中棋子（来自棋盘内点选，预留）
        if (clickFromRow >= 0 && clickFromCol >= 0) {
            int sx = snapScreenX(clickFromCol), sy = snapScreenY(clickFromRow);
            g.setColor(new Color(255, 220, 0, 120));
            g.fillOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
            g.setColor(new Color(255, 180, 0, 220));
            g.setStroke(new BasicStroke(2.5f));
            g.drawOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
        }
        // 点选棋子库后，显示棋盘上所有合法格高亮
        if (clickSelectedPiece != null) {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++) {
                    if (canPlace(clickSelectedPiece, r, c)) {
                        int sx = snapScreenX(c), sy = snapScreenY(r);
                        g.setColor(new Color(0, 200, 0, 40));
                        g.fillOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
                        g.setColor(new Color(0, 200, 0, 100));
                        g.setStroke(new BasicStroke(1.2f));
                        g.drawOval(sx - PIECE_R, sy - PIECE_R, PIECE_R*2, PIECE_R*2);
                    }
                }
        }
    }

    // =========================================================
    // ── 坐标转换（支持棋盘翻转）
    // =========================================================
    /** 逻辑格(row,col)→屏幕中心X */
    private int snapScreenX(int col) {
        int screenCol = boardFlipped ? 8 - col : col;
        return MARGIN + screenCol * CELL;
    }
    /** 逻辑格(row,col)→屏幕中心Y */
    private int snapScreenY(int row) {
        int screenRow = boardFlipped ? 9 - row : row;
        return MARGIN + screenRow * CELL;
    }
    /** 像素坐标 → 逻辑格[row,col]（超出返回null） */
    private int[] pixelToGrid(int px, int py) {
        int screenCol = Math.round((float)(px - MARGIN) / CELL);
        int screenRow = Math.round((float)(py - MARGIN) / CELL);
        if (screenRow < 0 || screenRow > 9 || screenCol < 0 || screenCol > 8) return null;
        int row = boardFlipped ? 9 - screenRow : screenRow;
        int col = boardFlipped ? 8 - screenCol : screenCol;
        return new int[]{row, col};
    }
    /** 吸附到最近格子，距离超过 CELL/2 则返回null（返回逻辑坐标） */
    private int[] snapToGrid(int px, int py) {
        int screenCol = Math.round((float)(px - MARGIN) / CELL);
        int screenRow = Math.round((float)(py - MARGIN) / CELL);
        if (screenRow < 0 || screenRow > 9 || screenCol < 0 || screenCol > 8) return null;
        int cx = MARGIN + screenCol * CELL, cy = MARGIN + screenRow * CELL;
        if (Math.abs(px - cx) > CELL/2 || Math.abs(py - cy) > CELL/2) return null;
        int row = boardFlipped ? 9 - screenRow : screenRow;
        int col = boardFlipped ? 8 - screenCol : screenCol;
        return new int[]{row, col};
    }

    // =========================================================
    // ── 棋子库面板（右侧，顺序联动棋盘翻转）
    // =========================================================
    private JPanel createPalettePanel() {
        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.setBorder(new EmptyBorder(6, 4, 6, 8));
        side.setPreferredSize(new Dimension(130, BOARD_H));
        side.setBackground(new Color(0xF5E6C8));

        JLabel title = new JLabel("棋子库", SwingConstants.CENTER);
        title.setFont(new Font("宋体", Font.BOLD, 13));
        title.setForeground(new Color(0x5C3317));
        title.setBorder(new EmptyBorder(0,0,4,0));

        // 红方区域
        JPanel redSection = new JPanel(new BorderLayout(0,2));
        redSection.setOpaque(false);
        JLabel redTitle = new JLabel("红方", SwingConstants.CENTER);
        redTitle.setFont(new Font("宋体", Font.BOLD, 11));
        redTitle.setForeground(new Color(0xAA0000));
        palettePanelRed = buildPaletteGrid(true);
        redSection.add(redTitle, BorderLayout.NORTH);
        redSection.add(palettePanelRed, BorderLayout.CENTER);

        // 黑方区域
        JPanel blackSection = new JPanel(new BorderLayout(0,2));
        blackSection.setOpaque(false);
        JLabel blackTitle = new JLabel("黑方", SwingConstants.CENTER);
        blackTitle.setFont(new Font("宋体", Font.BOLD, 11));
        blackTitle.setForeground(new Color(0x222222));
        palettePanelBlack = buildPaletteGrid(false);
        blackSection.add(blackTitle, BorderLayout.NORTH);
        blackSection.add(palettePanelBlack, BorderLayout.CENTER);

        // paletteContainer：顺序随翻转而定
        // boardFlipped=false（红方在下）→ 黑方在上，红方在下（对应人/AI视角）
        // boardFlipped=true（红方在上）→ 红方在上，黑方在下
        paletteContainer = new JPanel(new GridLayout(2, 1, 0, 8));
        paletteContainer.setOpaque(false);
        if (boardFlipped) {
            // 翻转=红方棋盘在上 → 棋子库红方在上，黑方在下
            paletteContainer.add(redSection);
            paletteContainer.add(blackSection);
        } else {
            // 未翻转=红方棋盘在下 → 棋子库黑方在上，红方在下
            paletteContainer.add(blackSection);
            paletteContainer.add(redSection);
        }

        // 先手选择
        JPanel turnPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        turnPanel.setOpaque(false);
        turnPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "先走方",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.PLAIN, 10), new Color(0x8B4513)));
        JRadioButton redBtn   = new JRadioButton("红方", editRedTurn);
        JRadioButton blackBtn = new JRadioButton("黑方", !editRedTurn);
        redBtn.setFont(new Font("宋体", Font.PLAIN, 10));
        blackBtn.setFont(new Font("宋体", Font.PLAIN, 10));
        redBtn.setOpaque(false); blackBtn.setOpaque(false);
        ButtonGroup bg = new ButtonGroup();
        bg.add(redBtn); bg.add(blackBtn);
        redBtn.addActionListener(e   -> editRedTurn = true);
        blackBtn.addActionListener(e -> editRedTurn = false);
        turnPanel.add(redBtn); turnPanel.add(blackBtn);

        side.add(title, BorderLayout.NORTH);
        side.add(paletteContainer, BorderLayout.CENTER);
        side.add(turnPanel, BorderLayout.SOUTH);
        return side;
    }

    /** 构建某方的棋子库面板（7行，每行：棋子图标 + 数量标签） */
    private JPanel buildPaletteGrid(boolean isRed) {
        JPanel grid = new JPanel(new GridLayout(7, 1, 0, 2));
        grid.setOpaque(false);
        JLabel[] labels = new JLabel[7];
        if (isRed) redCountLabels = labels;
        else blackCountLabels = labels;

        for (int i = 0; i < PIECE_TYPES.length; i++) {
            final int idx = i;
            Piece.Type t = PIECE_TYPES[i];
            final Piece proto = new Piece(t, isRed);

            JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
            rowPanel.setOpaque(false);

            // 棋子按钮
            JButton btn = new JButton() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int rem = getRemaining(proto.type, proto.isRed);
                    float alpha = rem > 0 ? 1f : 0.35f;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    int r2 = Math.min(getWidth(), getHeight())/2 - 2;
                    if (r2 < 4) return;
                    int cx = getWidth()/2, cy = getHeight()/2;
                    boolean isSelected = (this == clickSelectedBtn);
                    Color bg2 = isSelected ? new Color(0x44CCFF)
                              : (isRed ? new Color(0xFFD700) : new Color(0xE8D8B0));
                    g2.setColor(bg2);
                    g2.fillOval(cx-r2, cy-r2, r2*2, r2*2);
                    g2.setColor(isSelected ? new Color(0x0055AA)
                              : (isRed ? new Color(0xB22222) : new Color(0x333333)));
                    g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1.5f));
                    g2.drawOval(cx-r2, cy-r2, r2*2, r2*2);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                    g2.setColor(isRed ? new Color(0xAA0000) : new Color(0x111111));
                    g2.setFont(new Font("宋体", Font.BOLD, 13));
                    FontMetrics fm = g2.getFontMetrics();
                    String txt = proto.getDisplay();
                    g2.drawString(txt, cx - fm.stringWidth(txt)/2, cy + fm.getAscent()/2 - 1);
                }
            };
            btn.setPreferredSize(new Dimension(30, 30));
            btn.setBorderPainted(false); btn.setContentAreaFilled(false); btn.setFocusPainted(false);
            btn.setToolTipText(getTooltip(t, isRed));

            // 数量标签
            JLabel countLbl = new JLabel("", SwingConstants.CENTER);
            countLbl.setFont(new Font("宋体", Font.PLAIN, 10));
            labels[i] = countLbl;

            rowPanel.add(btn, BorderLayout.WEST);
            rowPanel.add(countLbl, BorderLayout.CENTER);

            // ─── 统一鼠标处理：拖拽 + 点选 ───
            MouseAdapter handler = new MouseAdapter() {
                /** 是否已进入拖拽状态（移动超过阈值） */
                private boolean dragging = false;
                private int pressX, pressY;

                @Override public void mousePressed(MouseEvent e) {
                    if (getRemaining(proto.type, proto.isRed) <= 0) {
                        setHint("⚠ " + (isRed?"红":"黑") + proto.getDisplay() + " 数量已达上限！");
                        return;
                    }
                    dragging = false;
                    pressX = e.getX(); pressY = e.getY();
                    // 先不立即触发拖拽，等 mouseDragged 确认是真的拖拽
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (getRemaining(proto.type, proto.isRed) <= 0) return;
                    if (!dragging) {
                        int dx = Math.abs(e.getX() - pressX), dy = Math.abs(e.getY() - pressY);
                        if (dx < 5 && dy < 5) return; // 未超过拖拽阈值
                        // 确认进入拖拽模式：取消点选
                        dragging = true;
                        cancelClickSelect();
                        dragPiece = proto.copy();
                        dragFromBoard = false;
                        dragFromRow = -1; dragFromCol = -1;
                    }
                    if (dragPiece == null) return;
                    Point p = SwingUtilities.convertPoint(btn, e.getPoint(), boardPanel);
                    dragX = p.x; dragY = p.y;
                    boardPanel.repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (dragging && dragPiece != null) {
                        // 拖拽结束：放到棋盘
                        Point p = SwingUtilities.convertPoint(btn, e.getPoint(), boardPanel);
                        int[] snap = snapToGrid(p.x, p.y);
                        if (snap != null && canPlace(dragPiece, snap[0], snap[1])) {
                            editBoard.grid[snap[0]][snap[1]] = dragPiece;
                            updatePaletteCountLabels();
                            boardPanel.repaint();
                        } else {
                            setHint(snap != null ? "⚠ 该格不符合棋子放置规则" : "请将棋子拖入棋盘");
                        }
                        dragPiece = null; dragX = -1; dragY = -1;
                        boardPanel.repaint();
                        dragging = false;
                    } else if (!dragging) {
                        // 点击：切换点选状态
                        if (getRemaining(proto.type, proto.isRed) <= 0) return;
                        if (clickSelectedBtn == btn) {
                            // 再次点击同一个 → 取消选中
                            cancelClickSelect();
                        } else {
                            cancelClickSelect();
                            clickSelectedPiece = proto.copy();
                            clickSelectedBtn = btn;
                            btn.repaint();
                            setHint("已选中 " + (isRed?"红":"黑") + proto.getDisplay()
                                    + "，点棋盘合法格放置；右键或ESC取消");
                            boardPanel.repaint(); // 显示合法格高亮
                        }
                    }
                }
            };
            btn.addMouseListener(handler);
            btn.addMouseMotionListener(handler);

            grid.add(rowPanel);
        }
        return grid;
    }

    // =========================================================
    // ── 棋子数量 / 规则
    // =========================================================
    private int getCount(Piece.Type t, boolean isRed) {
        int cnt = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = editBoard.grid[r][c];
                if (p != null && p.type == t && p.isRed == isRed) cnt++;
            }
        return cnt;
    }

    private int getRemaining(Piece.Type t, boolean isRed) {
        return getMax(t) - getCount(t, isRed);
    }

    private int getMax(Piece.Type t) {
        switch (t) {
            case KING:     return MAX_KING;
            case ADVISOR:  return MAX_ADVISOR;
            case ELEPHANT: return MAX_ELEPHANT;
            case HORSE:    return MAX_HORSE;
            case ROOK:     return MAX_ROOK;
            case CANNON:   return MAX_CANNON;
            case PAWN:     return MAX_PAWN;
            default:       return 1;
        }
    }

    /**
     * 检查棋子是否可以放在(row,col)（逻辑坐标）。
     * 坐标：行0=黑方底线，行9=红方底线
     */
    private boolean canPlace(Piece piece, int row, int col) {
        if (row < 0 || row > 9 || col < 0 || col > 8) return false;
        // 数量检查（替换同类同色不增加数量）
        Piece existing = editBoard.grid[row][col];
        if (existing == null || !(existing.type == piece.type && existing.isRed == piece.isRed)) {
            if (getCount(piece.type, piece.isRed) >= getMax(piece.type)) return false;
        }
        // 位置限制
        switch (piece.type) {
            case KING:
            case ADVISOR:
                // 九宫：红方 row=7-9, col=3-5；黑方 row=0-2, col=3-5
                if (piece.isRed) return row >= 7 && row <= 9 && col >= 3 && col <= 5;
                else             return row >= 0 && row <= 2 && col >= 3 && col <= 5;
            case ELEPHANT:
                // 相/象不过河：红方 row=5-9；黑方 row=0-4
                if (piece.isRed) return row >= 5;
                else             return row <= 4;
            case PAWN:
                // 兵/卒不能放在己方腹地（卒林线以内）
                // 行5=红方卒林线，行4=黑方卒林线
                // 红兵合法区域：行0-5（对方区域+卒林线），不能放行6-9
                // 黑卒合法区域：行4-9（对方区域+卒林线），不能放行0-3
                if (piece.isRed) return row <= 5;
                else             return row >= 4;
            default:
                return true; // 马/车/炮 全盘任意
        }
    }

    private String getTooltip(Piece.Type t, boolean isRed) {
        int max = getMax(t);
        String side = isRed ? "红" : "黑";
        switch (t) {
            case KING:     return side + (isRed?"帅":"将") + "：最多" + max + "个，只能在己方九宫";
            case ADVISOR:  return side + (isRed?"仕":"士") + "：最多" + max + "个，只能在己方九宫";
            case ELEPHANT: return side + (isRed?"相":"象") + "：最多" + max + "个，不能过河";
            case HORSE:    return side + (isRed?"马":"馬") + "：最多" + max + "个，全盘任意";
            case ROOK:     return side + (isRed?"车":"車") + "：最多" + max + "个，全盘任意";
            case CANNON:   return side + (isRed?"炮":"砲") + "：最多" + max + "个，全盘任意";
            case PAWN:     return side + (isRed?"兵":"卒") + "：最多" + max + "个，只能在卒林线及以前（含过河）";
            default:       return "";
        }
    }

    private void updatePaletteCountLabels() {
        for (int i = 0; i < PIECE_TYPES.length; i++) {
            int remRed   = getRemaining(PIECE_TYPES[i], true);
            int remBlack = getRemaining(PIECE_TYPES[i], false);
            if (redCountLabels != null && redCountLabels[i] != null) {
                redCountLabels[i].setText("剩" + remRed);
                redCountLabels[i].setForeground(remRed > 0 ? new Color(0x006600) : new Color(0x999999));
            }
            if (blackCountLabels != null && blackCountLabels[i] != null) {
                blackCountLabels[i].setText("剩" + remBlack);
                blackCountLabels[i].setForeground(remBlack > 0 ? new Color(0x006600) : new Color(0x999999));
            }
        }
        if (palettePanelRed != null)   palettePanelRed.repaint();
        if (palettePanelBlack != null) palettePanelBlack.repaint();
    }

    private void setHint(String msg) {
        if (hintLabel != null) hintLabel.setText(msg);
    }

    // =========================================================
    // ── 底部操作栏
    // =========================================================
    private JPanel createBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(new Color(0xF5E6C8));
        bottom.setBorder(new EmptyBorder(4, 8, 6, 8));

        hintLabel = new JLabel("从右侧棋子库拖拽或点选棋子到棋盘；棋盘内可拖动；拖出棋盘外删除", SwingConstants.LEFT);
        hintLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        hintLabel.setForeground(new Color(0x555555));
        bottom.add(hintLabel, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);

        JButton clearBtn = makeBtn("清空", () -> {
            cancelClickSelect();
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    editBoard.grid[r][c] = null;
            updatePaletteCountLabels();
            boardPanel.repaint();
        });
        JButton resetBtn = makeBtn("初始布局", () -> {
            cancelClickSelect();
            editBoard.initBoard();
            updatePaletteCountLabels();
            boardPanel.repaint();
        });
        JButton confirmBtn = makeBtn("✅ 确认", () -> {
            String err = validateBoard();
            if (err != null) {
                JOptionPane.showMessageDialog(this, err, "棋局检查", JOptionPane.WARNING_MESSAGE);
                return;
            }
            for (WindowListener wl : getWindowListeners()) removeWindowListener(wl);
            dispose();
            onConfirm.accept(editBoard, editRedTurn);
        });
        confirmBtn.setForeground(new Color(0x005500));
        confirmBtn.setFont(new Font("宋体", Font.BOLD, 12));
        JButton cancelBtn = makeBtn("取消", () -> {
            for (WindowListener wl : getWindowListeners()) removeWindowListener(wl);
            dispose();
            onCancel.run();
        });

        btns.add(clearBtn); btns.add(resetBtn); btns.add(cancelBtn); btns.add(confirmBtn);
        bottom.add(btns, BorderLayout.EAST);
        return bottom;
    }

    private JButton makeBtn(String txt, Runnable action) {
        JButton b = new JButton(txt);
        b.setFont(new Font("宋体", Font.PLAIN, 12));
        b.addActionListener(e -> action.run());
        return b;
    }

    // =========================================================
    // ── 绘制
    // =========================================================
    private void drawBoard(Graphics2D g) {
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(1.3f));
        // 横线（逻辑行0-9，显示按翻转映射）
        for (int r = 0; r < 10; r++) {
            int sy = MARGIN + r * CELL;
            g.drawLine(MARGIN, sy, MARGIN + 8 * CELL, sy);
        }
        // 竖线
        for (int c = 0; c < 9; c++) {
            int sx = MARGIN + c * CELL;
            if (c == 0 || c == 8) {
                g.drawLine(sx, MARGIN, sx, MARGIN + 9*CELL);
            } else {
                g.drawLine(sx, MARGIN,          sx, MARGIN + 4*CELL);
                g.drawLine(sx, MARGIN + 5*CELL, sx, MARGIN + 9*CELL);
            }
        }
        // 九宫斜线（固定在屏幕坐标的上下两端）
        g.drawLine(MARGIN + 3*CELL, MARGIN,          MARGIN + 5*CELL, MARGIN + 2*CELL);
        g.drawLine(MARGIN + 5*CELL, MARGIN,          MARGIN + 3*CELL, MARGIN + 2*CELL);
        g.drawLine(MARGIN + 3*CELL, MARGIN + 7*CELL, MARGIN + 5*CELL, MARGIN + 9*CELL);
        g.drawLine(MARGIN + 5*CELL, MARGIN + 7*CELL, MARGIN + 3*CELL, MARGIN + 9*CELL);
        g.setFont(new Font("宋体", Font.BOLD, 15));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", MARGIN + CELL,    MARGIN + 4*CELL + 22);
        g.drawString("汉  界", MARGIN + 5*CELL,  MARGIN + 4*CELL + 22);
        // 行列标注
        g.setFont(new Font("宋体", Font.PLAIN, 10));
        g.setColor(new Color(0x999999));
        for (int c = 0; c < 9; c++) {
            String s = String.valueOf(c + 1);
            g.drawString(s, MARGIN + c*CELL - 4, MARGIN - 6);
            g.drawString(s, MARGIN + c*CELL - 4, MARGIN + 9*CELL + 14);
        }
    }

    private void drawPieces(Graphics2D g) {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = editBoard.grid[r][c];
                if (p == null) continue;
                // 使用逻辑坐标映射到屏幕坐标（支持翻转）
                int sx = snapScreenX(c), sy = snapScreenY(r);
                drawPieceAt(g, p, sx, sy, PIECE_R);
            }
    }

    private void drawPieceAt(Graphics2D g, Piece p, int cx, int cy, int r) {
        Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
        Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
        Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);
        g.setColor(new Color(0, 0, 0, 40));
        g.fillOval(cx - r + 2, cy - r + 3, r*2, r*2);
        g.setColor(bg);
        g.fillOval(cx - r, cy - r, r*2, r*2);
        g.setColor(border);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(cx - r, cy - r, r*2, r*2);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(cx - r + 3, cy - r + 3, (r-3)*2, (r-3)*2);
        g.setColor(text);
        g.setFont(new Font("宋体", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        String txt = p.getDisplay();
        g.drawString(txt, cx - fm.stringWidth(txt)/2, cy + fm.getAscent()/2 - 1);
    }

    // =========================================================
    // ── 校验
    // =========================================================
    private String validateBoard() {
        int redKing = 0, blackKing = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = editBoard.grid[r][c];
                if (p == null) continue;
                if (p.type == Piece.Type.KING) {
                    if (p.isRed) redKing++; else blackKing++;
                }
            }
        if (redKing != 1) return "红方必须有且仅有一个「帅」";
        if (blackKing != 1) return "黑方必须有且仅有一个「将」";
        return null;
    }

    @Override public void setVisible(boolean b) {
        if (b) updatePaletteCountLabels();
        super.setVisible(b);
    }
}
