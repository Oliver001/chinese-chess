package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.BiConsumer;

/**
 * 编辑棋局对话框（拖拽版）：
 *  - 底部：棋子库面板（显示可用棋子及剩余数量）
 *  - 中央：棋盘（支持从棋子库拖入、棋盘内拖动、拖出棋盘删除）
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
 *   兵/卒：只限制不能放在己方底线（无实战意义）
 *           红兵不能放行9（己方底线），黑卒不能放行0（己方底线）
 *           对方底线行（行0/行9）允许放置，残局摆谱需要
 *   马/车/炮：全盘任意位置
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

    // ---- 拖拽状态 ----
    /** 拖拽中的棋子 */
    private Piece dragPiece = null;
    /** 拖拽起点（棋盘内格子，若从棋子库拖入则为-1,-1） */
    private int dragFromRow = -1, dragFromCol = -1;
    /** 当前鼠标位置（屏幕坐标，用于绘制拖拽棋子） */
    private int dragX = -1, dragY = -1;
    /** 拖拽是否来自棋盘（否则来自棋子库） */
    private boolean dragFromBoard = false;

    // ---- UI ----
    private JPanel boardPanel;
    private JPanel palettePanelRed;
    private JPanel palettePanelBlack;
    private JLabel hintLabel;
    private JLabel[] redCountLabels;   // 红方各棋子剩余数量标签
    private JLabel[] blackCountLabels; // 黑方各棋子剩余数量标签
    private static final Piece.Type[] PIECE_TYPES = {
        Piece.Type.KING, Piece.Type.ADVISOR, Piece.Type.ELEPHANT,
        Piece.Type.HORSE, Piece.Type.ROOK, Piece.Type.CANNON, Piece.Type.PAWN
    };

    /** 确认回调 */
    private final BiConsumer<Board, Boolean> onConfirm;
    /** 取消回调 */
    private final Runnable onCancel;

    public EditBoardDialog(Component parent, Board currentBoard, boolean currentRedTurn,
                           BiConsumer<Board, Boolean> onConfirm, Runnable onCancel) {
        super(SwingUtilities.getWindowAncestor(parent), "摆谱 / 编辑棋局",
              ModalityType.APPLICATION_MODAL);
        this.onConfirm = onConfirm;
        this.onCancel  = onCancel;

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

        pack();
        setResizable(true);
        setMinimumSize(new Dimension(660, 560));
        setLocationRelativeTo(parent);
    }

    // =========================================================
    // ── 棋盘面板（支持拖拽）
    // =========================================================
    private JPanel createBoardPanel() {
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawPieces(g2);
                // 拖拽时在鼠标位置绘制浮动棋子
                if (dragPiece != null && dragX >= 0) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f));
                    drawPieceAt(g2, dragPiece, dragX, dragY, PIECE_R + 2);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
                    // 目标格高亮
                    int[] snap = snapToGrid(dragX, dragY);
                    if (snap != null) {
                        boolean ok = canPlace(dragPiece, snap[0], snap[1]);
                        g2.setColor(ok ? new Color(0, 200, 0, 80) : new Color(220, 0, 0, 80));
                        g2.fillOval(MARGIN + snap[1]*CELL - PIECE_R, MARGIN + snap[0]*CELL - PIECE_R,
                                PIECE_R*2, PIECE_R*2);
                        g2.setColor(ok ? new Color(0, 200, 0, 180) : new Color(220, 0, 0, 180));
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawOval(MARGIN + snap[1]*CELL - PIECE_R, MARGIN + snap[0]*CELL - PIECE_R,
                                PIECE_R*2, PIECE_R*2);
                    }
                }
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));

        // ---- 鼠标事件：处理拖拽 ----
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int[] rc = pixelToGrid(e.getX(), e.getY());
                if (rc == null) return;
                Piece p = editBoard.grid[rc[0]][rc[1]];
                if (p == null) return;
                // 开始从棋盘拖拽
                dragPiece = p;
                dragFromRow = rc[0]; dragFromCol = rc[1];
                dragFromBoard = true;
                dragX = e.getX(); dragY = e.getY();
                // 暂时移除棋子（拖拽期间）
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
                    // 放到目标格
                    Piece old = editBoard.grid[snap[0]][snap[1]];
                    editBoard.grid[snap[0]][snap[1]] = dragPiece;
                    // 如果原来有棋子被替换（不同棋子），且是从棋盘拖来的 → 原棋子直接消失
                    // 若拖拽被取消（放到棋盘外或非法格）→ 恢复
                } else if (dragFromBoard) {
                    // 释放在棋盘外或非法格 → 删除（相当于从棋盘移除）
                    // 如果用户把棋子拖回原位或棋盘外：拖到棋盘外=删除，拖到原格=保留
                    if (snap != null && snap[0]==dragFromRow && snap[1]==dragFromCol) {
                        editBoard.grid[dragFromRow][dragFromCol] = dragPiece; // 还回去
                    }
                    // 拖出棋盘外则删除（不恢复），棋子从棋盘消失
                }
                dragPiece = null; dragX = -1; dragY = -1;
                dragFromRow = -1; dragFromCol = -1;
                updatePaletteCountLabels();
                boardPanel.repaint();
            }
        };
        boardPanel.addMouseListener(ma);
        boardPanel.addMouseMotionListener(ma);

        // ---- 支持从棋子库面板拖入（通过全局鼠标事件转发）----
        // 当从 palettePanel 拖拽时，鼠标事件可能离开 palettePanel 进入 boardPanel
        // 通过 SwingUtilities.convertMouseEvent 处理跨组件拖拽
        return boardPanel;
    }

    /** 像素坐标 → 棋盘格子[row,col]，超出返回null */
    private int[] pixelToGrid(int px, int py) {
        int col = Math.round((float)(px - MARGIN) / CELL);
        int row = Math.round((float)(py - MARGIN) / CELL);
        if (row < 0 || row > 9 || col < 0 || col > 8) return null;
        return new int[]{row, col};
    }

    /** 吸附到最近格子，距离超过 CELL/2 则返回null */
    private int[] snapToGrid(int px, int py) {
        int col = Math.round((float)(px - MARGIN) / CELL);
        int row = Math.round((float)(py - MARGIN) / CELL);
        if (row < 0 || row > 9 || col < 0 || col > 8) return null;
        int cx = MARGIN + col * CELL, cy = MARGIN + row * CELL;
        if (Math.abs(px - cx) > CELL/2 || Math.abs(py - cy) > CELL/2) return null;
        return new int[]{row, col};
    }

    // =========================================================
    // ── 棋子库面板（右侧，支持拖拽到棋盘）
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

        JPanel content = new JPanel(new GridLayout(2, 1, 0, 8));
        content.setOpaque(false);

        // 红方棋子
        JPanel redSection = new JPanel(new BorderLayout(0,2));
        redSection.setOpaque(false);
        JLabel redTitle = new JLabel("红方", SwingConstants.CENTER);
        redTitle.setFont(new Font("宋体", Font.BOLD, 11));
        redTitle.setForeground(new Color(0xAA0000));
        palettePanelRed = buildPaletteGrid(true);
        redSection.add(redTitle, BorderLayout.NORTH);
        redSection.add(palettePanelRed, BorderLayout.CENTER);
        content.add(redSection);

        // 黑方棋子
        JPanel blackSection = new JPanel(new BorderLayout(0,2));
        blackSection.setOpaque(false);
        JLabel blackTitle = new JLabel("黑方", SwingConstants.CENTER);
        blackTitle.setFont(new Font("宋体", Font.BOLD, 11));
        blackTitle.setForeground(new Color(0x222222));
        palettePanelBlack = buildPaletteGrid(false);
        blackSection.add(blackTitle, BorderLayout.NORTH);
        blackSection.add(palettePanelBlack, BorderLayout.CENTER);
        content.add(blackSection);

        side.add(title, BorderLayout.NORTH);
        side.add(content, BorderLayout.CENTER);

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

            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setOpaque(false);

            // 棋子按钮（可点击触发拖拽）
            JButton btn = new JButton() {
                boolean pressed = false;
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int rem = getRemaining(proto.type, proto.isRed);
                    float alpha = rem > 0 ? 1f : 0.35f;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    int r2 = Math.min(getWidth(), getHeight())/2 - 2;
                    if (r2 < 4) return;
                    int cx = getWidth()/2, cy = getHeight()/2;
                    Color bg = pressed ? new Color(0xFFEE44)
                            : (isRed ? new Color(0xFFD700) : new Color(0xE8D8B0));
                    g2.setColor(bg);
                    g2.fillOval(cx-r2, cy-r2, r2*2, r2*2);
                    g2.setColor(isRed ? new Color(0xB22222) : new Color(0x333333));
                    g2.setStroke(new BasicStroke(pressed ? 2f : 1.5f));
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

            row.add(btn, BorderLayout.WEST);
            row.add(countLbl, BorderLayout.CENTER);

            // ---- 拖拽逻辑：按下棋子库按钮 → 开始拖拽 ----
            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (getRemaining(proto.type, proto.isRed) <= 0) {
                        hintLabel.setText("⚠ " + (isRed?"红":"黑") + proto.getDisplay() + " 数量已达上限！");
                        return;
                    }
                    dragPiece = proto.copy();
                    dragFromBoard = false;
                    dragFromRow = -1; dragFromCol = -1;
                    // 初始拖拽位置：转换为 boardPanel 坐标系
                    Point p = SwingUtilities.convertPoint(btn, e.getPoint(), boardPanel);
                    dragX = p.x; dragY = p.y;
                    boardPanel.repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragPiece == null) return;
                    Point p = SwingUtilities.convertPoint(btn, e.getPoint(), boardPanel);
                    dragX = p.x; dragY = p.y;
                    boardPanel.repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (dragPiece == null) return;
                    Point p = SwingUtilities.convertPoint(btn, e.getPoint(), boardPanel);
                    int[] snap = snapToGrid(p.x, p.y);
                    if (snap != null && canPlace(dragPiece, snap[0], snap[1])) {
                        editBoard.grid[snap[0]][snap[1]] = dragPiece;
                        updatePaletteCountLabels();
                        boardPanel.repaint();
                    } else {
                        hintLabel.setText(snap != null ? "⚠ 该格不符合棋子放置规则" : "请将棋子拖入棋盘");
                    }
                    dragPiece = null; dragX = -1; dragY = -1;
                    boardPanel.repaint();
                }
            };
            btn.addMouseListener(drag);
            btn.addMouseMotionListener(drag);

            grid.add(row);
        }
        return grid;
    }

    /** 获取某种棋子在棋盘上的当前数量 */
    private int getCount(Piece.Type t, boolean isRed) {
        int cnt = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = editBoard.grid[r][c];
                if (p != null && p.type == t && p.isRed == isRed) cnt++;
            }
        return cnt;
    }

    /** 获取某种棋子还可以放多少个 */
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
     * 检查棋子是否可以放在(row,col)。
     * 规则：
     * 1. 该格已有同类同色棋子 → 不允许（等于没有空位）
     * 2. 帅/将、仕/士 → 只能在九宫内
     * 3. 相/象 → 只能在己方半边
     * 4. 兵/卒 → 不能放在己方底线（红兵不能在行9，黑卒不能在行0）
     *             残局中兵/卒可以到达对方底线，摆谱应允许
     * 5. 数量未超上限
     */
    private boolean canPlace(Piece piece, int row, int col) {
        if (row < 0 || row > 9 || col < 0 || col > 8) return false;
        // 数量检查（若该格已有同类棋子算替换，不计入数量）
        Piece existing = editBoard.grid[row][col];
        int cnt = getCount(piece.type, piece.isRed);
        if (existing != null && !(existing.type == piece.type && existing.isRed == piece.isRed)) {
            // 替换不同棋子，当前数量不变
        }
        if (existing == null || !(existing.type == piece.type && existing.isRed == piece.isRed)) {
            // 需要额外放一个
            if (cnt >= getMax(piece.type)) return false;
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
                // 象走田格，位置必须在偶数格（可以放宽，让规则引擎负责合法性）
                if (piece.isRed) return row >= 5;
                else             return row <= 4;
            case PAWN:
                // 兵/卒可以放全盘任意位置（残局中兵/卒可以到达对方底线）
                // 坐标：行0=黑方底线，行9=红方底线
                // 只限制不能放在己方底线（无实战意义）
                // 红兵不能放行9（红方自己底线），黑卒不能放行0（黑方自己底线）
                if (piece.isRed) return row <= 8; // 红兵可以到达行0（黑方底线），不能放行9（己方底线）
                else             return row >= 1; // 黑卒可以到达行9（红方底线），不能放行0（己方底线）
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
            case PAWN:     return side + (isRed?"兵":"卒") + "：最多" + max + "个，可全盘放置（含对方底线）";
            default:       return "";
        }
    }

    /** 更新棋子库所有数量标签 */
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

    // =========================================================
    // ── 底部操作栏
    // =========================================================
    private JPanel createBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBackground(new Color(0xF5E6C8));
        bottom.setBorder(new EmptyBorder(4, 8, 6, 8));

        hintLabel = new JLabel("从右侧棋子库拖拽棋子到棋盘；棋盘内也可拖动；拖出棋盘外可删除", SwingConstants.LEFT);
        hintLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        hintLabel.setForeground(new Color(0x555555));
        bottom.add(hintLabel, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.setOpaque(false);

        JButton clearBtn = makeBtn("清空", () -> {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    editBoard.grid[r][c] = null;
            updatePaletteCountLabels();
            boardPanel.repaint();
        });
        JButton resetBtn = makeBtn("初始布局", () -> {
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
        for (int r = 0; r < 10; r++)
            g.drawLine(MARGIN, MARGIN + r * CELL, MARGIN + 8 * CELL, MARGIN + r * CELL);
        for (int c = 0; c < 9; c++) {
            if (c == 0 || c == 8)
                g.drawLine(MARGIN + c*CELL, MARGIN, MARGIN + c*CELL, MARGIN + 9*CELL);
            else {
                g.drawLine(MARGIN + c*CELL, MARGIN, MARGIN + c*CELL, MARGIN + 4*CELL);
                g.drawLine(MARGIN + c*CELL, MARGIN + 5*CELL, MARGIN + c*CELL, MARGIN + 9*CELL);
            }
        }
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
                drawPieceAt(g, p, MARGIN + c*CELL, MARGIN + r*CELL, PIECE_R);
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

    /** 在对话框显示时刷新数量标签 */
    @Override public void setVisible(boolean b) {
        if (b) updatePaletteCountLabels();
        super.setVisible(b);
    }
}
