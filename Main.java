
import javax.swing.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Base64;

public class Main {

    static final String DB_URL =
            "jdbc:mysql://localhost:3306/secure_voting";
    static final String DB_USER = "root";

    static final String DB_PASSWORD = "Divya_vani@123";

    static final String[] CANDIDATES = {
        "Candidate A", "Candidate B", "Candidate C"
    };

    static SecretKey secretKey;
    static String loggedInVoter = null;
    static int electionId = 1;

    static Connection connectDB() throws SQLException {
        return DriverManager.getConnection(
            DB_URL, DB_USER, DB_PASSWORD
        );
    }

    static {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            secretKey = generator.generateKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String hashPassword(String password) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(
            password.getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getEncoder().encodeToString(hash);
    }

    static String encryptVote(String vote) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(
            vote.getBytes(StandardCharsets.UTF_8)
        );
        return Base64.getEncoder().encodeToString(encrypted);
    }

    static void logActivity(String activity) {
        String sql = "INSERT INTO audit_log (activity) VALUES (?)";
        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, activity);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("Audit log error: " + e.getMessage());
        }
    }

    static void setupElection() throws Exception {
        try (Connection conn = connectDB()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT election_id FROM elections ORDER BY election_id LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {

                if (rs.next()) {
                    electionId = rs.getInt("election_id");
                } else {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO elections (election_name, election_status) VALUES (?, 'Active')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, "Student Election");
                        insert.executeUpdate();

                        try (ResultSet keys = insert.getGeneratedKeys()) {
                            if (keys.next()) {
                                electionId = keys.getInt(1);
                            }
                        }
                    }
                }
            }

            for (String candidate : CANDIDATES) {
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT candidate_id FROM candidates WHERE election_id = ? AND candidate_name = ?")) {
                    check.setInt(1, electionId);
                    check.setString(2, candidate);

                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            try (PreparedStatement insert = conn.prepareStatement(
                                    "INSERT INTO candidates (election_id, candidate_name) VALUES (?, ?)")) {
                                insert.setInt(1, electionId);
                                insert.setString(2, candidate);
                                insert.executeUpdate();
                            }
                        }
                    }
                }
            }
        }
    }

    static void registerVoter(JFrame frame) {
        String name = JOptionPane.showInputDialog(
            frame, "Enter a voter name:"
        );
        if (name == null || name.trim().isEmpty()) return;

        String password = JOptionPane.showInputDialog(
            frame, "Create a password:"
        );
        if (password == null || password.isEmpty()) return;

        String sql = "INSERT INTO voters (voter_name, password_hash) VALUES (?, ?)";

        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            ps.setString(2, hashPassword(password));
            ps.executeUpdate();

            logActivity("Voter registered: " + name.trim());
            JOptionPane.showMessageDialog(frame, "Registration successful!");
        } catch (SQLIntegrityConstraintViolationException e) {
            JOptionPane.showMessageDialog(frame, "That voter name already exists.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Registration failed: " + e.getMessage()
            );
        }
    }

    static void loginVoter(JFrame frame) {
        String name = JOptionPane.showInputDialog(
            frame, "Enter your voter name:"
        );
        if (name == null || name.trim().isEmpty()) return;

        String password = JOptionPane.showInputDialog(
            frame, "Enter your password:"
        );
        if (password == null || password.isEmpty()) return;

        String sql = "SELECT password_hash FROM voters WHERE voter_name = ?";

        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() &&
                    rs.getString("password_hash").equals(hashPassword(password))) {
                    loggedInVoter = name.trim();
                    logActivity("Voter logged in: " + loggedInVoter);
                    JOptionPane.showMessageDialog(frame, "Login successful!");
                } else {
                    JOptionPane.showMessageDialog(
                        frame, "Invalid voter name or password."
                    );
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Login failed: " + e.getMessage()
            );
        }
    }

    static void castVote(JFrame frame) {
        if (loggedInVoter == null) {
            JOptionPane.showMessageDialog(frame, "Please log in first.");
            return;
        }

        String choice = (String) JOptionPane.showInputDialog(
            frame, "Select your candidate:", "Cast Vote",
            JOptionPane.QUESTION_MESSAGE, null, CANDIDATES, CANDIDATES[0]
        );
        if (choice == null) return;

        try (Connection conn = connectDB()) {
            conn.setAutoCommit(false);

            try {
        
                try (PreparedStatement check = conn.prepareStatement(
                        "SELECT has_voted FROM voters WHERE voter_name = ? FOR UPDATE")) {
                    check.setString(1, loggedInVoter);

                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next() || rs.getBoolean("has_voted")) {
                            conn.rollback();
                            JOptionPane.showMessageDialog(
                                frame, "You have already voted or your account was not found."
                            );
                            return;
                        }
                    }
                }

                int candidateId = -1;

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT candidate_id FROM candidates WHERE election_id = ? AND candidate_name = ?")) {
                    ps.setInt(1, electionId);
                    ps.setString(2, choice);

                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) candidateId = rs.getInt("candidate_id");
                    }
                }

                if (candidateId == -1) {
                    conn.rollback();
                    JOptionPane.showMessageDialog(frame, "Candidate not found.");
                    return;
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO votes (election_id, candidate_id, encrypted_vote) VALUES (?, ?, ?)")) {
                    ps.setInt(1, electionId);
                    ps.setInt(2, candidateId);
                    ps.setString(3, encryptVote(choice));
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE voters SET has_voted = TRUE WHERE voter_name = ?")) {
                    ps.setString(1, loggedInVoter);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO audit_log (activity) VALUES (?)")) {
                    ps.setString(1, "Vote recorded for voter: " + loggedInVoter);
                    ps.executeUpdate();
                }

                conn.commit();
                JOptionPane.showMessageDialog(frame, "Vote recorded successfully!");

            } catch (Exception e) {
                conn.rollback();
                JOptionPane.showMessageDialog(
                    frame, "Could not record vote: " + e.getMessage()
                );
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Database error: " + e.getMessage()
            );
        }
    }

    static void showResults(JFrame frame) {
        StringBuilder result = new StringBuilder("Election Results\n\n");
        String sql =
            "SELECT c.candidate_name, COUNT(v.vote_id) AS total " +
            "FROM candidates c LEFT JOIN votes v " +
            "ON c.candidate_id = v.candidate_id AND v.election_id = c.election_id " +
            "WHERE c.election_id = ? GROUP BY c.candidate_id, c.candidate_name";

        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, electionId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.append(rs.getString("candidate_name"))
                          .append(": ")
                          .append(rs.getInt("total"))
                          .append(" votes\n");
                }
            }

            JOptionPane.showMessageDialog(frame, result.toString());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Could not load results: " + e.getMessage()
            );
        }
    }

    static void showTurnout(JFrame frame) {
        String sql =
            "SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN has_voted = TRUE THEN 1 ELSE 0 END) AS voted " +
            "FROM voters";

        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int total = rs.getInt("total");
                int voted = rs.getInt("voted");
                JOptionPane.showMessageDialog(
                    frame, "Registered voters: " + total +
                    "\nVoters who voted: " + voted +
                    "\nTurnout: " +
                    (total == 0 ? 0 : (voted * 100.0 / total)) + "%"
                );
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Could not load turnout: " + e.getMessage()
            );
        }
    }

    static void showAuditLog(JFrame frame) {
        StringBuilder text = new StringBuilder("Audit Log\n\n");

        try (Connection conn = connectDB();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT activity, created_at FROM audit_log ORDER BY log_id DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                text.append(rs.getTimestamp("created_at"))
                    .append(" - ")
                    .append(rs.getString("activity"))
                    .append("\n");
            }
            JTextArea area = new JTextArea(text.toString(), 15, 40);
            area.setEditable(false);
            JOptionPane.showMessageDialog(
                frame, new JScrollPane(area), "Audit Log",
                JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                frame, "Could not load audit log: " + e.getMessage()
            );
        }
    }

    public static void main(String[] args) {
        try {
            setupElection();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                null, "Database setup failed: " + e.getMessage()
            );
            return;
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Secure Electronic Voting System");
            frame.setSize(430, 370);
            frame.setLayout(null);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null);

            JLabel title = new JLabel("Secure Electronic Voting System");
            title.setBounds(55, 15, 330, 30);
            frame.add(title);

            JButton register = new JButton("Register Voter");
            register.setBounds(120, 55, 180, 30);
            register.addActionListener(e -> registerVoter(frame));
            frame.add(register);

            JButton login = new JButton("Voter Login");
            login.setBounds(120, 95, 180, 30);
            login.addActionListener(e -> loginVoter(frame));
            frame.add(login);

            JButton vote = new JButton("Cast Vote");
            vote.setBounds(120, 135, 180, 30);
            vote.addActionListener(e -> castVote(frame));
            frame.add(vote);

            JButton results = new JButton("View Results");
            results.setBounds(120, 175, 180, 30);
            results.addActionListener(e -> showResults(frame));
            frame.add(results);

            JButton turnout = new JButton("View Turnout");
            turnout.setBounds(120, 215, 180, 30);
            turnout.addActionListener(e -> showTurnout(frame));
            frame.add(turnout);

            JButton audit = new JButton("Audit Log");
            audit.setBounds(120, 255, 180, 30);
            audit.addActionListener(e -> showAuditLog(frame));
            frame.add(audit);

            frame.setVisible(true);
        });
    }
}
