import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class Server {
    public static final int PORT = 12000;
    public static final String HISTORY_FILE = "history.html";

    public static void main(String[] args) {
        // Clear history file on server start
        try (PrintWriter writer = new PrintWriter(HISTORY_FILE)) {
            writer.print("");
        } catch (IOException e) {
            System.err.println("[ERROR] Could not clear history.html");
        }

        // Start listening for client connections
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[Python-based Server Started] Listening on port " + PORT + "...");
            while (true) {
                try {
                    Socket client = server.accept(); // Accept new client
                    System.out.println("[NEW CONNECTION] " + formatAddr(client) + " connected.");
                    new Thread(new ClientHandler(client)).start(); // Handle client in new thread
                } catch (IOException e) {
                    System.err.println("[ERROR] accept() failed: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Return formatted string for IP:Port
    public static String formatAddr(Socket s) {
        InetSocketAddress remote = (InetSocketAddress) s.getRemoteSocketAddress();
        String ip = remote.getAddress().getHostAddress();
        int port = remote.getPort();
        return "('" + ip + "', " + port + ")";
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private final String socketAddr;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.socketAddr = formatAddr(socket);
        }

        public void run() {
            try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                while (true) {
                    String requestLine = in.readLine();
                    if (requestLine == null) continue;

                    // Handle HTTP GET requests
                    if (requestLine.startsWith("GET")) {
                        handleHttpRequest(requestLine, out);
                        // Skip header lines
                        while ((requestLine = in.readLine()) != null && !requestLine.trim().isEmpty()) {}
                        continue;
                    }

                    // Evaluate math expressions
                    double result = evaluateExpression(requestLine, out);
                    if (!Double.isNaN(result)) {
                        System.out.println("[" + socketAddr + "] " + requestLine + " = " + result);
                        appendToHistory(requestLine + " = " + result);
                        out.println("Result = " + result);
                    }
                }
            } catch (IOException e) {
                System.err.println("[ERROR] I/O: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) { e.printStackTrace(); }
            }
        }

        // Handle HTTP GET requests for files
        private void handleHttpRequest(String requestLine, PrintWriter out) {
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || parts[1].trim().isEmpty()) {
                out.print("HTTP/1.1 404 File Not Found\r\n\r\n");
                out.flush();
                System.out.println("[FILE REQUEST] Client " + socketAddr + " requested invalid path: EMPTY");
                System.out.println("[FILE MISSING] '' not found for " + socketAddr);
                return;
            }

            String path = parts[1];
            if (path.startsWith("/")) path = path.substring(1);

            System.out.println("[FILE REQUEST] Client " + socketAddr + " requested: " + path);

            File file = new File(path);
            if (!file.exists()) {
                out.print("HTTP/1.1 404 File Not Found\r\n\r\n");
                out.flush();
                System.out.println("[FILE MISSING] '" + path + "' not found for " + socketAddr);
                return;
            }

            // Send file content
            try {
                BufferedReader br = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                out.print("HTTP/1.1 200 OK\r\n\r\n");
                out.print(sb.toString());
                out.flush();
                System.out.println("[FILE FOUND] Sending '" + path + "' to " + socketAddr);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Evaluate full math expression
        private double evaluateExpression(String expr, PrintWriter out) {
            expr = expr.toLowerCase().replaceAll("\\s+", "");
            if (expr.isEmpty()) return Double.NaN;

            List<String> finalTokens = twoPhaseTokenize(expr, out);
            if (finalTokens == null) return Double.NaN;

            List<String> postfix = toPostfix(finalTokens);
            return evalPostfix(postfix, out);
        }

        // Tokenize input in two passes: raw tokens + merge signs
        private List<String> twoPhaseTokenize(String expr, PrintWriter out) {
            List<String> rawTokens = new ArrayList<>();
            Matcher m = Pattern.compile("(squareroot|square|\\d+(\\.\\d+)?|\\*|/|\\+|-)").matcher(expr);
            int lastEnd = 0;
            while (m.find()) {
                if (m.start() > lastEnd) {
                    String invalid = expr.substring(lastEnd, m.start());
                    logInvalidToken(invalid, out);
                    return null;
                }
                rawTokens.add(m.group());
                lastEnd = m.end();
            }
            if (lastEnd != expr.length()) {
                String invalid = expr.substring(lastEnd);
                logInvalidToken(invalid, out);
                return null;
            }

            // Merge sign with next token if needed (e.g., -5, +square)
            List<String> merged = new ArrayList<>();
            for (int i = 0; i < rawTokens.size(); i++) {
                String t = rawTokens.get(i);
                if ((t.equals("+") || t.equals("-"))) {
                    if (i == 0 || isOperatorOrUnary(rawTokens.get(i - 1))) {
                        if (i + 1 < rawTokens.size()) {
                            String next = rawTokens.get(i + 1);
                            if (isNumber(next) || isPureUnary(next)) {
                                merged.add(t + next);
                                i++;
                                continue;
                            }
                        }
                    }
                    merged.add(t);
                } else {
                    merged.add(t);
                }
            }

            return merged;
        }

        private boolean isOperatorOrUnary(String x) {
            return "+-*/".contains(x) || isUnary(x);
        }

        // Print error and notify client
        private void logInvalidToken(String token, PrintWriter out) {
            System.out.println("[" + socketAddr + "] [ERROR] Invalid token: '" + token + "'");
            out.println("'" + token + "' is invalid expression. Please try again.");
        }

        // Convert infix tokens to postfix using Shunting Yard
        private List<String> toPostfix(List<String> tokens) {
            List<String> output = new ArrayList<>();
            Stack<String> ops = new Stack<>();
            for (String token : tokens) {
                if (isNumber(token)) {
                    output.add(token);
                } else if (isUnary(token)) {
                    ops.push(token);
                } else if ("+-*/".contains(token)) {
                    while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(token)) {
                        output.add(ops.pop());
                    }
                    ops.push(token);
                }
            }
            while (!ops.isEmpty()) output.add(ops.pop());
            return output;
        }

        // Evaluate postfix expression
        private double evalPostfix(List<String> postfix, PrintWriter out) {
            Stack<Double> stack = new Stack<>();
            try {
                for (String token : postfix) {
                    if (isNumber(token)) {
                        stack.push(Double.parseDouble(token));
                    } else if (isUnary(token)) {
                        double a = stack.pop();
                        boolean sign = token.startsWith("+") || token.startsWith("-");
                        String op = token;
                        if (sign && token.length() > 1) op = token.substring(1);

                        double r;
                        if (op.contains("squareroot")) {
                            if (a < 0) {
                                logInvalidSqrt(a, out);
                                return Double.NaN;
                            }
                            r = manualSqrt(a);
                        } else if (op.contains("square")) {
                            r = a * a;
                        } else {
                            r = a;
                        }

                        // Apply sign
                        if (sign && token.startsWith("-")) r = -r;
                        stack.push(r);
                    } else {
                        double b = stack.pop();
                        double a = stack.pop();
                        switch(token) {
                            case "+": stack.push(a + b); break;
                            case "-": stack.push(a - b); break;
                            case "*": stack.push(a * b); break;
                            case "/": stack.push(a / b); break;
                        }
                    }
                }
                return stack.pop();
            } catch (EmptyStackException e) {
                System.out.println("[" + socketAddr + "] [ERROR] Not enough operands for expression");
                out.println("Not enough operands for expression. Please try again.");
                return Double.NaN;
            }
        }

        // Handle sqrt of negative
        private void logInvalidSqrt(double val, PrintWriter out) {
            System.out.println("[" + socketAddr + "] [ERROR] Attempted sqrt of negative: " + val);
            out.println("Cannot take squareroot of negative number. Please try again.");
        }

        private boolean isPureUnary(String s) {
            return s.equals("square") || s.equals("squareroot");
        }

        private boolean isUnary(String s) {
            return s.contains("square");
        }

        private boolean isNumber(String s) {
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // Define operator precedence
        private int precedence(String op) {
            if (op.contains("square") || op.contains("squareroot")) return 3;
            if (op.equals("*") || op.equals("/")) return 2;
            if (op.equals("+") || op.equals("-")) return 1;
            return 0;
        }

        // Manual sqrt using Newton's method
        private double manualSqrt(double x) {
            if (x < 0) return Double.NaN;
            double guess = x / 2.0;
            for (int i = 0; i < 20; i++) {
                guess = (guess + x / guess) / 2.0;
            }
            return guess;
        }

        // Append a record to history.html
        private synchronized void appendToHistory(String record) {
            try (FileWriter fw = new FileWriter(HISTORY_FILE, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(record);
                bw.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
