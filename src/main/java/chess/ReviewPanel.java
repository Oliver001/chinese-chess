package chess;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.List;

/**
 * 打谱面板：加载历史对局，逐步回放并展示优势折线图。
 * 可点击棋谱列表的任意一步跳转复盘。
 */
public class ReviewPanel extends JPanel {

    // ---- 尺寸（与ChessPanel一致）----
    private static final int CELL    = 60;
    private static final int MARGIN  = 40;
    private static final int PIECE_R = 24;
    private static final int BOARD_W = MARGIN * 2 + CELL * 8;
    private static final int BOARD_H = MARGIN * 2 + CELL * 9;
    private static final int SIDE_W  = 220;
    private static final int CHART_H = 130;

    private GameState.GameRecord record;
    /** 当前显示到第几步（0=初始局面，1=第一步走完，…）*/
    private int currentStep = 0;
    /** 重建局面用的 board */
    private final Board board = new Board();

    // ---- UI ----
    private JPanel boardPanel;
    private JList<String> moveList;
    private DefaultListModel<String> moveListModel;
    private JLabel stepLabel;
    private JLabel resultLabel;
    private ScoreChart scoreChart;
    private JButton prevBtn, nextBtn, firstBtn, lastBtn;

    public ReviewPanel(GameState.GameRecord record) {
        this.record = record;
        setLayout(new BorderLayout(4, 4));
        setBackground(new Color(0xEEE0B0));
        setBorder(new EmptyBorder(8,8,8,8));

        buildUI();
        gotoStep(0);
    }

    // =====================================================================
    // UI 构建
    // =====================================================================
    private void buildUI() {
        // 左：棋盘
        boardPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D)g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                drawBoard(g2);
                drawPieces(g2);
            }
        };
        boardPanel.setPreferredSize(new Dimension(BOARD_W, BOARD_H));
        boardPanel.setBackground(new Color(0xDEB887));
        add(boardPanel, BorderLayout.CENTER);

        // 右：信息面板
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setPreferredSize(new Dimension(SIDE_W, BOARD_H));
        right.setOpaque(false);

        // 顶部：对局信息
        JPanel info = new JPanel(new GridLayout(3,1,2,2));
        info.setOpaque(false);
        resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(new Font("宋体", Font.BOLD, 13));
        resultLabel.setForeground(new Color(0x8B0000));
        stepLabel   = new JLabel("", SwingConstants.CENTER);
        stepLabel.setFont(new Font("宋体", Font.PLAIN, 12));
        JLabel dateLabel = new JLabel(record.date, SwingConstants.CENTER);
        dateLabel.setFont(new Font("宋体", Font.PLAIN, 11));
        dateLabel.setForeground(new Color(0x666666));
        info.add(resultLabel);
        info.add(stepLabel);
        info.add(dateLabel);
        right.add(info, BorderLayout.NORTH);

        // 中：走法列表（可点击）
        moveListModel = new DefaultListModel<>();
        moveListModel.addElement("  初始局面");
        for (int i = 0; i < record.notations.size(); i++) {
            String nota = record.notations.get(i);
            String prefix = (i%2==0) ? String.format("%3d. ", i/2+1) : "     ";
            moveListModel.addElement(prefix + nota);
        }
        moveList = new JList<>(moveListModel);
        moveList.setFont(new Font("宋体", Font.PLAIN, 12));
        moveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        moveList.setBackground(new Color(0xFFFAF0));
        moveList.setFixedCellHeight(20);
        moveList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = moveList.getSelectedIndex();
                if (idx >= 0) gotoStep(idx);
            }
        });
        JScrollPane listScroll = new JScrollPane(moveList);
        listScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "棋谱",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 12), new Color(0x8B4513)));
        right.add(listScroll, BorderLayout.CENTER);

        // 底部：优势折线图 + 翻页按钮
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setOpaque(false);

        scoreChart = new ScoreChart();
        scoreChart.setPreferredSize(new Dimension(SIDE_W, CHART_H));
        scoreChart.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0xC8A060)), "优势曲线",
            TitledBorder.CENTER, TitledBorder.TOP,
            new Font("宋体", Font.BOLD, 11), new Color(0x8B4513)));
        scoreChart.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int step = scoreChart.hitTest(e.getX(), e.getY());
                if (step >= 0) gotoStep(step);
            }
        });
        bottom.add(scoreChart, BorderLayout.NORTH);

        // 翻页按钮
        JPanel nav = new JPanel(new GridLayout(1,4,4,0));
        nav.setOpaque(false);
        firstBtn = makeNavBtn("|◀", e -> gotoStep(0));
        prevBtn  = makeNavBtn("◀",  e -> gotoStep(Math.max(0, currentStep-1)));
        nextBtn  = makeNavBtn("▶",  e -> gotoStep(Math.min(record.moves.size(), currentStep+1)));
        lastBtn  = makeNavBtn("▶|", e -> gotoStep(record.moves.size()));
        nav.add(firstBtn); nav.add(prevBtn); nav.add(nextBtn); nav.add(lastBtn);
        bottom.add(nav, BorderLayout.SOUTH);

        right.add(bottom, BorderLayout.SOUTH);
        add(right, BorderLayout.EAST);
    }

    private JButton makeNavBtn(String text, ActionListener al) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Dialog", Font.BOLD, 14));
        btn.addActionListener(al);
        return btn;
    }

    // =====================================================================
    // 跳转到第 step 步
    // =====================================================================
    private void gotoStep(int step) {
        step = Math.max(0, Math.min(record.moves.size(), step));
        currentStep = step;

        // 重建局面
        board.initBoard();
        for (int i = 0; i < step; i++) {
            int[] mv = record.moves.get(i);
            board.move(mv[0], mv[1], mv[2], mv[3]);
        }

        // 更新列表选中
        final int finalStep = step;
        SwingUtilities.invokeLater(() -> {
            moveList.setSelectedIndex(finalStep);
            moveList.ensureIndexIsVisible(finalStep);
        });

        // 更新标签
        int total = record.moves.size();
        stepLabel.setText("第 " + step + " 步 / 共 " + total + " 步");
        resultLabel.setText("结果：" + record.result +
            "  " + (record.humanIsRed?"执红":"执黑") +
            "  难度:" + record.difficulty);

        // 更新按钮状态
        firstBtn.setEnabled(step > 0);
        prevBtn.setEnabled(step > 0);
        nextBtn.setEnabled(step < total);
        lastBtn.setEnabled(step < total);

        // 更新折线图高亮
        scoreChart.setCurrentStep(step);

        boardPanel.repaint();
    }

    // =====================================================================
    // 绘制棋盘
    // =====================================================================
    private void drawBoard(Graphics2D g) {
        g.setColor(new Color(0x8B4513));
        g.setStroke(new BasicStroke(1.5f));
        for (int r=0;r<10;r++)
            g.drawLine(MARGIN, MARGIN+r*CELL, MARGIN+8*CELL, MARGIN+r*CELL);
        for (int c=0;c<9;c++) {
            if (c==0||c==8) g.drawLine(MARGIN+c*CELL, MARGIN, MARGIN+c*CELL, MARGIN+9*CELL);
            else {
                g.drawLine(MARGIN+c*CELL, MARGIN,        MARGIN+c*CELL, MARGIN+4*CELL);
                g.drawLine(MARGIN+c*CELL, MARGIN+5*CELL, MARGIN+c*CELL, MARGIN+9*CELL);
            }
        }
        g.drawLine(MARGIN+3*CELL,MARGIN,           MARGIN+5*CELL,MARGIN+2*CELL);
        g.drawLine(MARGIN+5*CELL,MARGIN,           MARGIN+3*CELL,MARGIN+2*CELL);
        g.drawLine(MARGIN+3*CELL,MARGIN+7*CELL,    MARGIN+5*CELL,MARGIN+9*CELL);
        g.drawLine(MARGIN+5*CELL,MARGIN+7*CELL,    MARGIN+3*CELL,MARGIN+9*CELL);
        g.setFont(new Font("宋体", Font.BOLD, 16));
        g.setColor(new Color(0x5C3317));
        g.drawString("楚  河", MARGIN+CELL,   MARGIN+4*CELL+26);
        g.drawString("汉  界", MARGIN+5*CELL, MARGIN+4*CELL+26);
        // 高亮当前步的走法
        if (currentStep > 0) {
            int[] mv = record.moves.get(currentStep - 1);
            g.setColor(new Color(160, 210, 80, 100));
            g.fillOval(MARGIN+mv[1]*CELL-PIECE_R, MARGIN+mv[0]*CELL-PIECE_R, PIECE_R*2, PIECE_R*2);
            g.fillOval(MARGIN+mv[3]*CELL-PIECE_R, MARGIN+mv[2]*CELL-PIECE_R, PIECE_R*2, PIECE_R*2);
        }
    }

    private void drawPieces(Graphics2D g) {
        for (int r=0;r<10;r++) for (int c=0;c<9;c++) {
            Piece p = board.grid[r][c];
            if (p==null) continue;
            int cx=MARGIN+c*CELL, cy=MARGIN+r*CELL;
            Color bg     = p.isRed ? new Color(0xFFD700) : new Color(0xF0DEB0);
            Color border = p.isRed ? new Color(0xB22222) : new Color(0x333333);
            Color text   = p.isRed ? new Color(0xB22222) : new Color(0x111111);
            g.setColor(new Color(0,0,0,40));
            g.fillOval(cx-PIECE_R+2, cy-PIECE_R+3, PIECE_R*2, PIECE_R*2);
            g.setColor(bg);
            g.fillOval(cx-PIECE_R,cy-PIECE_R,PIECE_R*2,PIECE_R*2);
            g.setColor(border);
            g.setStroke(new BasicStroke(2f));
            g.drawOval(cx-PIECE_R,cy-PIECE_R,PIECE_R*2,PIECE_R*2);
            g.setStroke(new BasicStroke(1f));
            g.drawOval(cx-PIECE_R+3,cy-PIECE_R+3,(PIECE_R-3)*2,(PIECE_R-3)*2);
            g.setColor(text);
            g.setFont(new Font("宋体", Font.BOLD, 17));
            FontMetrics fm = g.getFontMetrics();
            String txt = p.getDisplay();
            g.drawString(txt, cx-fm.stringWidth(txt)/2, cy+fm.getAscent()/2-1);
        }
    }

    // =====================================================================
    // 优势折线图
    // =====================================================================
    class ScoreChart extends JPanel {
        private int highlightStep = 0;

        void setCurrentStep(int s) { highlightStep = s; repaint(); }

        /**
         * 鼠标命中测试：返回点击对应的步数（0=初始），-1=未命中
         */
        int hitTest(int mx, int my) {
            if (record.scores.isEmpty()) return -1;
            int n = record.scores.size();
            int w = getWidth() - 30, h = getHeight() - 36;
            int ox = 15, oy = 18;
            if (n <= 1) return -1;
            for (int i = 0; i <= n; i++) {
                int x = ox + (int)(i * (double)w / n);
                if (Math.abs(mx - x) <= 8) return i;
            }
            return -1;
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D)g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            List<Integer> sc = record.scores;
            int n = sc.size();
            int w = getWidth() - 30, h = getHeight() - 36;
            int ox = 15, oy = 18;

            // 背景
            g2.setColor(new Color(0xFFFAF0));
            g2.fillRect(ox, oy, w, h);
            g2.setColor(new Color(0xC8A060));
            g2.drawRect(ox, oy, w, h);

            if (n == 0) {
                g2.setColor(Color.GRAY);
                g2.setFont(new Font("宋体",Font.PLAIN,11));
                g2.drawString("暂无数据", ox+w/2-20, oy+h/2);
                return;
            }

            // 零线（均势）
            int zeroY = oy + h/2;
            g2.setColor(new Color(0xAAAAAA));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                         1f, new float[]{4,3}, 0));
            g2.drawLine(ox, zeroY, ox+w, zeroY);

            // 坐标转换（将分值映射到像素）
            // ±1000分 ≈ ±h/3；超过±2000饱和
            final int SCALE = 2000;

            // 绘制折线（渐变填充）
            if (n >= 2) {
                // 先画填充面积
                int[] xPts = new int[n+2], yPts = new int[n+2];
                for (int i = 0; i < n; i++) {
                    xPts[i+1] = ox + (int)((i+1) * (double)w / n);
                    int clipped = Math.max(-SCALE, Math.min(SCALE, sc.get(i)));
                    yPts[i+1] = zeroY - (int)(clipped * (h/2.0) / SCALE);
                }
                xPts[0] = ox; yPts[0] = zeroY;
                xPts[n+1] = ox+w; yPts[n+1] = zeroY;

                Polygon poly = new Polygon(xPts, yPts, n+2);
                GradientPaint gp = new GradientPaint(0, oy, new Color(0xCC2222,true),
                                                     0, zeroY, new Color(0xFF8888,true));
                // 红色区域（红优）
                g2.setClip(ox, oy, w, h/2);
                g2.setPaint(new Color(0xCC222233, true));
                g2.fillPolygon(poly);
                // 黑色区域（黑优）
                g2.setClip(ox, zeroY, w, h/2);
                g2.setPaint(new Color(0x22222233, true));
                g2.fillPolygon(poly);
                g2.setClip(null);

                // 折线
                g2.setStroke(new BasicStroke(2f));
                for (int i = 1; i < n; i++) {
                    int x1 = ox + (int)(i * (double)w / n);
                    int x2 = ox + (int)((i+1) * (double)w / n);
                    int c1 = Math.max(-SCALE, Math.min(SCALE, sc.get(i-1)));
                    int c2 = Math.max(-SCALE, Math.min(SCALE, sc.get(i)));
                    int y1 = zeroY - (int)(c1 * (h/2.0) / SCALE);
                    int y2 = zeroY - (int)(c2 * (h/2.0) / SCALE);
                    g2.setColor(sc.get(i) >= 0 ? new Color(0xAA1111) : new Color(0x111111));
                    g2.drawLine(x1,y1,x2,y2);
                }
            }

            // 当前步高亮（竖线 + 圆点）
            if (highlightStep > 0 && highlightStep <= n) {
                int hx = ox + (int)(highlightStep * (double)w / n);
                g2.setColor(new Color(0x3399FF));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(hx, oy, hx, oy+h);
                // 评分点
                int score = sc.get(highlightStep-1);
                int clipped = Math.max(-SCALE, Math.min(SCALE, score));
                int hy = zeroY - (int)(clipped * (h/2.0) / SCALE);
                g2.setColor(new Color(0x3399FF));
                g2.fillOval(hx-4,hy-4,8,8);
                // 评分文字
                g2.setFont(new Font("宋体",Font.BOLD,10));
                String scoreStr = (score > 0 ? "红+" : score < 0 ? "黑+" : "均") +
                                  (score != 0 ? Math.abs(score) : "势");
                g2.drawString(scoreStr, Math.min(hx+4, ox+w-30), Math.max(hy-4, oy+12));
            }

            // 步数轴标（每10步一个标记）
            g2.setFont(new Font("Dialog",Font.PLAIN,9));
            g2.setColor(new Color(0x888888));
            for (int i = 0; i <= n; i += Math.max(1, n/8)) {
                int x = ox + (int)(i * (double)w / n);
                g2.drawString(String.valueOf(i), x-4, oy+h+12);
            }
        }
    }

    // =====================================================================
    // 静态工厂：弹出打谱对话框
    // =====================================================================
    public static void showReviewDialog(Component parent) {
        List<GameState.GameRecord> games = GameState.loadAllGames();
        if (games.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                "暂无历史对局记录。\n每局结束后会自动保存。",
                "历史棋谱", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 选择对局
        DefaultListModel<GameState.GameRecord> model = new DefaultListModel<>();
        for (GameState.GameRecord r : games) model.addElement(r);
        JList<GameState.GameRecord> gameList = new JList<>(model);
        gameList.setFont(new Font("宋体", Font.PLAIN, 13));
        gameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gameList.setSelectedIndex(0);
        JScrollPane sp = new JScrollPane(gameList);
        sp.setPreferredSize(new Dimension(380, 200));

        int ok = JOptionPane.showConfirmDialog(parent, sp,
            "选择对局（共" + games.size() + "局）",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;

        GameState.GameRecord selected = gameList.getSelectedValue();
        if (selected == null) return;

        // 打开打谱窗口
        JFrame frame = new JFrame("打谱 — " + selected.date);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        ReviewPanel panel = new ReviewPanel(selected);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(parent);
        frame.setVisible(true);
    }
}
