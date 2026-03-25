package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.BiConsumer;

/**
 * 编辑棋局对话框：
 *  - 左侧：棋盘，点击空格放置选中棋子，点击有棋子的格清除/换子
 *  - 右侧：棋子选择面板（红/黑各7种）+ 清空 / 恢复初始 / 确认 / 取消
 */
public class EditBoardDialog extends JDialog {

    private static final int CELL    = 56;
    private static final int MARGIN  = 36;
    private static final int PIECE_R = 22;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;

    /** 当前选中的棋子模板（null=橡皮擦模式） */
    private Piece selectedPiece = null;
    /** 编辑中的棋盘（副本，确认后才应用）*/
    private final Board editBoard = new Board();
    /** 当前轮走方 */
    private boolean editRedTurn = true;

    private JPanel boardPanel;
    private JLabel hintLabel;

    /** 确认回调：(newBoard, redTurn) */
    private final BiConsumer<Board, Boolean> onConfirm;
    /** 取消回调 */
    private final Runnable onCancel;

    public EditBoardDialog(Component parent, Board currentBoard, boolean currentRedTurn,
                           BiConsumer<Board, Boolean> onConfirm, Runnable onCancel) {
        super(SwingUtilities.getWindowAncestor(parent), "编辑棋局",
              ModalityType.APPLICATION_MODAL);
        this.onConfirm = onConfirm;
        this.onCancel  = onCancel;

        // 复制当前棋盘到编辑副本
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                editBoard.grid[r][c] = currentBoard.grid[r][c] != null
                        ? currentBoard.grid[r][c].copy() : null;
        editRedTurn = currentRedTurn;

        setLayout(new BorderLayout(6, 6));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { onCancel.run(); }
        });

        add(createBoardPanel(), BorderLayout.CENTER);
        add(createSidePanel(),  BorderLayout.EAST);

        hintLabel = new JLabel("选择右侧棋子后，点击棋盘放置；点击已有棋子可清除", SwingConstants.CENTER);
        hintLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        hintLabel.setForeground(new Color(0x666666));
        hintLabel.setBorder(new EmptyBorder(3, 6, 3, 6));
        add(hintLabel, BorderLayout.SOUTH);

        pack();
        setResizable(true);
        setMinimumSize(new Dimension(640, 520));
        setLocationRelativeTo(parent);
    }

    // ── 棋盘面板 ──────────────────────────────────────────────
    private JPanel createBoardPanel() {
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawPieces(g2);
                drawSelectedHighlight(g2);
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));
        boardPanel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                handleBoardClick(e.getX(), e.getY());
            }
        });
        return boardPanel;
    }

    private void handleBoardClick(int px, int py) {
        int col = Math.round((float)(px - MARGIN) / CELL);
        int row = Math.round((float)(py - MARGIN) / CELL);
        if (row < 0 || row > 9 || col < 0 || col > 8) return;

        Piece existing = editBoard.grid[row][col];
        if (selectedPiece == null) {
            // 橡皮擦：清除该格
            editBoard.grid[row][col] = null;
        } else {
            if (existing != null && existing.type == selectedPiece.type
                    && existing.isRed == selectedPiece.isRed) {
                // 再次点击同类棋子 → 清除
                editBoard.grid[row][col] = null;
            } else {
                editBoard.grid[row][col] = selectedPiece.copy();
            }
        }
        boardPanel.repaint();
        updateHint();
    }

    // ── 绘制 ──────────────────────────────────────────────────
    private void drawBoard(Graphics2D g) {
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(1.3f));
        for (int r = 0; r < 10; r++)
            g.drawLine(MARGIN, MARGIN + r * CELL, MARGIN + 8 * CELL, MARGIN + r * CELL);
        for (int c = 0; c < 9; c++) {
            if (c == 0 || c == 8) g.drawLine(MARGIN + c * CELL, MARGIN, MARGIN + c * CELL, MARGIN + 9 * CELL);
            else {
                g.drawLine(MARGIN + c * CELL, MARGIN,          MARGIN + c * CELL, MARGIN + 4 * CELL);
                g.drawLine(MARGIN + c * CELL, MARGIN + 5 * CELL, MARGIN + c * CELL, MARGIN + 9 * CELL);
            }
        }
        g.drawLine(MARGIN + 3 * CELL, MARGIN,            MARGIN + 5 * CELL, MARGIN + 2 * CELL);
        g.drawLine(MARGIN + 5 * CELL, MARGIN,            MARGIN + 3 * CELL, MARGIN + 2 * CELL);
        g.drawLine(MARGIN + 3 * CELL, MARGIN + 7 * CELL, MARGIN + 5 * CELL, MARGIN + 9 * CELL);
        g.drawLine(MARGIN + 5 * CELL, MARGIN + 7 * CELL, MARGIN + 3 * CELL, MARGIN + 9 * CELL);
        g.setFont(new Font("宋体", Font.BOLD, 15));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", MARGIN + CELL,     MARGIN + 4 * CELL + 22);
        g.drawString("汉  界", MARGIN + 5 * CELL,  MARGIN + 4 * CELL + 22);
        // 行列标注
        g.setFont(new Font("宋体", Font.PLAIN, 10));
        g.setColor(new Color(0x999999));
        for (int c = 0; c < 9; c++) {
            String s = String.valueOf(c + 1);
            g.drawString(s, MARGIN + c * CELL - 4, MARGIN - 6);
            g.drawString(s, MARGIN + c * CELL - 4, MARGIN + 9 * CELL + 14);
        }
    }

    private void drawPieces(Graphics2D g) {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = editBoard.grid[r][c];
                if (p == null) continue;
                int cx = MARGIN + c * CELL, cy = MARGIN + r * CELL;
                drawPiece(g, p, cx, cy, false);
            }
    }

    private void drawPiece(Graphics2D g, Piece p, int cx, int cy, boolean highlight) {
        Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
        Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
        Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);
        int r = PIECE_R;
        if (highlight) { bg = bg.brighter(); r += 2; }
        g.setColor(new Color(0, 0, 0, 40));
        g.fillOval(cx - r + 2, cy - r + 3, r * 2, r * 2);
        g.setColor(bg);
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(border);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(cx - r, cy - r, r * 2, r * 2);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(cx - r + 3, cy - r + 3, (r - 3) * 2, (r - 3) * 2);
        g.setColor(text);
        g.setFont(new Font("宋体", Font.BOLD, 16));
        FontMetrics fm = g.getFontMetrics();
        String txt = p.getDisplay();
        g.drawString(txt, cx - fm.stringWidth(txt) / 2, cy + fm.getAscent() / 2 - 1);
    }

    private void drawSelectedHighlight(Graphics2D g) {
        // 在棋盘右下角显示当前选中棋子
        if (selectedPiece != null) {
            g.setColor(new Color(255, 255, 0, 100));
            g.setStroke(new BasicStroke(3f));
            g.drawRoundRect(BOARD_W - 54, BOARD_H - 54, 48, 48, 10, 10);
            drawPiece(g, selectedPiece, BOARD_W - 30, BOARD_H - 30, true);
        }
    }

    // ── 右侧面板 ──────────────────────────────────────────────
    private JPanel createSidePanel() {
        JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setBorder(new EmptyBorder(6, 4, 6, 8));
        side.setPreferredSize(new Dimension(160, BOARD_H));
        side.setBackground(new Color(0xF5E6C8));

        // 棋子选择面板
        JPanel piecePanel = new JPanel(new BorderLayout(0, 4));
        piecePanel.setOpaque(false);

        JLabel redLabel = new JLabel("红方棋子", SwingConstants.CENTER);
        redLabel.setFont(new Font("宋体", Font.BOLD, 12));
        redLabel.setForeground(new Color(0xB22222));

        JPanel redPieces = makePieceGrid(true);
        JPanel blackPieces = makePieceGrid(false);

        JLabel blackLabel = new JLabel("黑方棋子", SwingConstants.CENTER);
        blackLabel.setFont(new Font("宋体", Font.BOLD, 12));
        blackLabel.setForeground(new Color(0x333333));

        // 橡皮擦按钮
        JButton eraser = new JButton("🧹 橡皮擦");
        eraser.setFont(new Font("宋体", Font.PLAIN, 11));
        eraser.addActionListener(e -> { selectedPiece = null; boardPanel.repaint(); });

        JPanel topPart = new JPanel(new GridLayout(5, 1, 0, 2));
        topPart.setOpaque(false);
        topPart.add(redLabel);
        topPart.add(redPieces);
        topPart.add(blackLabel);
        topPart.add(blackPieces);
        topPart.add(eraser);
        piecePanel.add(topPart, BorderLayout.NORTH);

        // 先手选择
        JPanel turnPanel = new JPanel(new GridLayout(1, 2, 4, 0));
        turnPanel.setOpaque(false);
        turnPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "先手方",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.PLAIN, 10), new Color(0x8B4513)));
        JRadioButton redBtn   = new JRadioButton("红方", editRedTurn);
        JRadioButton blackBtn = new JRadioButton("黑方", !editRedTurn);
        redBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        blackBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        redBtn.setOpaque(false); blackBtn.setOpaque(false);
        ButtonGroup bg = new ButtonGroup();
        bg.add(redBtn); bg.add(blackBtn);
        redBtn.addActionListener(e   -> editRedTurn = true);
        blackBtn.addActionListener(e -> editRedTurn = false);
        turnPanel.add(redBtn); turnPanel.add(blackBtn);

        // 底部按钮
        JPanel btnPanel = new JPanel(new GridLayout(4, 1, 4, 4));
        btnPanel.setOpaque(false);
        JButton clearBtn = new JButton("清空棋盘");
        clearBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        clearBtn.addActionListener(e -> {
            for (int r = 0; r < 10; r++)
                for (int c = 0; c < 9; c++)
                    editBoard.grid[r][c] = null;
            boardPanel.repaint();
        });
        JButton resetBtn = new JButton("恢复初始");
        resetBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        resetBtn.addActionListener(e -> { editBoard.initBoard(); boardPanel.repaint(); });
        JButton confirmBtn = new JButton("✅ 确认");
        confirmBtn.setFont(new Font("宋体", Font.BOLD, 12));
        confirmBtn.setForeground(new Color(0x006600));
        confirmBtn.addActionListener(e -> {
            String err = validateBoard();
            if (err != null) {
                JOptionPane.showMessageDialog(this, err, "棋局检查", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // 移除windowClosed监听的取消回调，改为确认
            for (WindowListener wl : getWindowListeners()) removeWindowListener(wl);
            dispose();
            onConfirm.accept(editBoard, editRedTurn);
        });
        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(new Font("宋体", Font.PLAIN, 11));
        cancelBtn.addActionListener(e -> {
            for (WindowListener wl : getWindowListeners()) removeWindowListener(wl);
            dispose();
            onCancel.run();
        });
        btnPanel.add(clearBtn);
        btnPanel.add(resetBtn);
        btnPanel.add(confirmBtn);
        btnPanel.add(cancelBtn);

        JPanel southPanel = new JPanel(new BorderLayout(0, 6));
        southPanel.setOpaque(false);
        southPanel.add(turnPanel, BorderLayout.NORTH);
        southPanel.add(btnPanel, BorderLayout.SOUTH);

        side.add(piecePanel, BorderLayout.CENTER);
        side.add(southPanel, BorderLayout.SOUTH);
        return side;
    }

    /** 创建7个棋子按钮的面板（1行7列） */
    private JPanel makePieceGrid(boolean isRed) {
        Piece.Type[] types = {
            Piece.Type.KING, Piece.Type.ADVISOR, Piece.Type.ELEPHANT,
            Piece.Type.HORSE, Piece.Type.ROOK, Piece.Type.CANNON, Piece.Type.PAWN
        };
        JPanel grid = new JPanel(new GridLayout(1, 7, 1, 1));
        grid.setOpaque(false);
        for (Piece.Type t : types) {
            Piece p = new Piece(t, isRed);
            JButton btn = new JButton(p.getDisplay()) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    // 背景
                    boolean sel = selectedPiece != null
                            && selectedPiece.type == p.type
                            && selectedPiece.isRed == p.isRed;
                    g2.setColor(sel ? new Color(0xFFEE44) : (isRed ? new Color(0xFFD700) : new Color(0xE8D8B0)));
                    g2.fillOval(2, 2, getWidth()-4, getHeight()-4);
                    g2.setColor(isRed ? new Color(0xB22222) : new Color(0x333333));
                    g2.setStroke(new BasicStroke(sel ? 2.5f : 1.5f));
                    g2.drawOval(2, 2, getWidth()-4, getHeight()-4);
                    g2.setStroke(new BasicStroke(1f));
                    // 文字
                    g2.setColor(isRed ? new Color(0xB22222) : new Color(0x111111));
                    g2.setFont(new Font("宋体", Font.BOLD, 12));
                    FontMetrics fm = g2.getFontMetrics();
                    String txt = p.getDisplay();
                    g2.drawString(txt, (getWidth()-fm.stringWidth(txt))/2,
                                  (getHeight()+fm.getAscent())/2-2);
                }
            };
            btn.setPreferredSize(new Dimension(22, 22));
            btn.setBorderPainted(false);
            btn.setContentAreaFilled(false);
            btn.setFocusPainted(false);
            btn.setToolTipText((isRed ? "红" : "黑") + p.getDisplay());
            btn.addActionListener(e -> {
                selectedPiece = p.copy();
                boardPanel.repaint();
                repaint();
            });
            grid.add(btn);
        }
        return grid;
    }

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

    private void updateHint() {
        if (selectedPiece == null) {
            hintLabel.setText("橡皮擦模式：点击棋子清除");
        } else {
            hintLabel.setText("已选：" + (selectedPiece.isRed ? "红" : "黑")
                    + selectedPiece.getDisplay() + "  点击棋盘放置，再次点击同格清除");
        }
    }
}
