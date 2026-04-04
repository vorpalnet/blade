package org.vorpal.blade.applications.console.config;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@WebServlet("/filemanager")
@ServerEndpoint("/websocket")
public class FileManagerServlet extends HttpServlet {
    
    private static final Logger logger = Logger.getLogger(FileManagerServlet.class.getName());
    private static final String DATA_FILE_PATH = "server_data.txt";
    private static final Set<Session> websocketSessions = new CopyOnWriteArraySet<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // WebSocket Event Handlers
    @OnOpen
    public void onOpen(Session session) {
        websocketSessions.add(session);
        logger.info("WebSocket connection opened. Session ID: " + session.getId());
        
        // Send current file content to newly connected client
        try {
            String fileContent = readFromFile();
            sendMessageToSession(session, createMessage("file_content", fileContent));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error sending initial file content", e);
        }
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        logger.info("Received WebSocket message: " + message);
        
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            String action = jsonNode.get("action").asText();
            String data = jsonNode.get("data").asText();
            
            switch (action) {
                case "write_file":
                    writeToFile(data);
                    broadcastMessage(createMessage("file_updated", data));
                    sendMessageToSession(session, createMessage("write_success", "File written successfully"));
                    break;
                    
                case "read_file":
                    String fileContent = readFromFile();
                    sendMessageToSession(session, createMessage("file_content", fileContent));
                    break;
                    
                case "append_file":
                    appendToFile(data);
                    String updatedContent = readFromFile();
                    broadcastMessage(createMessage("file_updated", updatedContent));
                    sendMessageToSession(session, createMessage("append_success", "Data appended successfully"));
                    break;
                    
                default:
                    sendMessageToSession(session, createMessage("error", "Unknown action: " + action));
            }
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error processing WebSocket message", e);
            sendMessageToSession(session, createMessage("error", "Error processing request: " + e.getMessage()));
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        websocketSessions.remove(session);
        logger.info("WebSocket connection closed. Session ID: " + session.getId());
    }
    
    @OnError
    public void onError(Session session, Throwable throwable) {
        logger.log(Level.SEVERE, "WebSocket error for session " + session.getId(), throwable);
        websocketSessions.remove(session);
    }
    
    // HTTP Servlet Methods
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        String action = request.getParameter("action");
        
        if ("download".equals(action)) {
            // Download file content
            response.setContentType("text/plain");
            response.setHeader("Content-Disposition", "attachment; filename=\"server_data.txt\"");
            
            try {
                String content = readFromFile();
                response.getWriter().write(content);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error reading file for download", e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error reading file");
            }
            
        } else {
            // Serve the HTML page
            response.setContentType("text/html");
            response.getWriter().write(getHtmlPage());
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws IOException {
        
        String action = request.getParameter("action");
        String data = request.getParameter("data");
        
        response.setContentType("application/json");
        
        try {
            switch (action) {
                case "write":
                    writeToFile(data);
                    // Broadcast to WebSocket clients
                    broadcastMessage(createMessage("file_updated", data));
                    response.getWriter().write("{\"status\":\"success\",\"message\":\"File written successfully\"}");
                    break;
                    
                case "read":
                    String content = readFromFile();
                    response.getWriter().write("{\"status\":\"success\",\"content\":\"" + 
                        content.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");
                    break;
                    
                case "append":
                    appendToFile(data);
                    String updatedContent = readFromFile();
                    // Broadcast to WebSocket clients
                    broadcastMessage(createMessage("file_updated", updatedContent));
                    response.getWriter().write("{\"status\":\"success\",\"message\":\"Data appended successfully\"}");
                    break;
                    
                default:
                    response.getWriter().write("{\"status\":\"error\",\"message\":\"Unknown action\"}");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error in POST request", e);
            response.getWriter().write("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
    
    // File Operation Methods
    private synchronized String readFromFile() throws IOException {
        Path filePath = Paths.get(DATA_FILE_PATH);
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
            return "";
        }
        return new String(Files.readAllBytes(filePath));
    }
    
    private synchronized void writeToFile(String content) throws IOException {
        Path filePath = Paths.get(DATA_FILE_PATH);
        Files.write(filePath, content.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("File written with content: " + content);
    }
    
    private synchronized void appendToFile(String content) throws IOException {
        Path filePath = Paths.get(DATA_FILE_PATH);
        if (!Files.exists(filePath)) {
            Files.createFile(filePath);
        }
        Files.write(filePath, (content + System.lineSeparator()).getBytes(), StandardOpenOption.APPEND);
        logger.info("Content appended to file: " + content);
    }
    
    // WebSocket Communication Methods
    private void sendMessageToSession(Session session, String message) {
        try {
            if (session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error sending message to session " + session.getId(), e);
        }
    }
    
    private void broadcastMessage(String message) {
        for (Session session : websocketSessions) {
            sendMessageToSession(session, message);
        }
    }
    
    private String createMessage(String type, String content) {
        try {
            return objectMapper.writeValueAsString(new Message(type, content));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error creating JSON message", e);
            return "{\"type\":\"error\",\"content\":\"Error creating message\"}";
        }
    }
    
    // HTML Page Generation
    private String getHtmlPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>File Manager with WebSocket</title>\n" +
                "    <style>\n" +
                "        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f5f5f5; }\n" +
                "        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n" +
                "        .status { padding: 10px; margin: 10px 0; border-radius: 4px; }\n" +
                "        .success { background-color: #d4edda; color: #155724; border: 1px solid #c3e6cb; }\n" +
                "        .error { background-color: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }\n" +
                "        .info { background-color: #d1ecf1; color: #0c5460; border: 1px solid #bee5eb; }\n" +
                "        textarea { width: 100%; height: 200px; margin: 10px 0; padding: 10px; border: 1px solid #ddd; border-radius: 4px; }\n" +
                "        button { background-color: #007bff; color: white; border: none; padding: 10px 20px; margin: 5px; border-radius: 4px; cursor: pointer; }\n" +
                "        button:hover { background-color: #0056b3; }\n" +
                "        .connection-status { font-weight: bold; }\n" +
                "        .connected { color: green; }\n" +
                "        .disconnected { color: red; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>File Manager with WebSocket</h1>\n" +
                "        \n" +
                "        <div class=\"status info\">\n" +
                "            WebSocket Status: <span id=\"connectionStatus\" class=\"connection-status disconnected\">Disconnected</span>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div id=\"statusMessages\"></div>\n" +
                "        \n" +
                "        <h3>File Content</h3>\n" +
                "        <textarea id=\"fileContent\" placeholder=\"File content will appear here...\"></textarea>\n" +
                "        \n" +
                "        <div>\n" +
                "            <button onclick=\"readFile()\">Read File</button>\n" +
                "            <button onclick=\"writeFile()\">Write File</button>\n" +
                "            <button onclick=\"appendToFile()\">Append to File</button>\n" +
                "            <button onclick=\"downloadFile()\">Download File</button>\n" +
                "        </div>\n" +
                "        \n" +
                "        <h3>Append New Data</h3>\n" +
                "        <textarea id=\"newData\" placeholder=\"Enter data to append...\"></textarea>\n" +
                "        <button onclick=\"appendNewData()\">Append New Data</button>\n" +
                "    </div>\n" +
                "\n" +
                "    <script>\n" +
                "        let websocket;\n" +
                "        const statusElement = document.getElementById('connectionStatus');\n" +
                "        const messagesElement = document.getElementById('statusMessages');\n" +
                "        const fileContentElement = document.getElementById('fileContent');\n" +
                "        \n" +
                "        function connectWebSocket() {\n" +
                "            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';\n" +
                "            const wsUrl = protocol + '//' + window.location.host + '/websocket';\n" +
                "            \n" +
                "            websocket = new WebSocket(wsUrl);\n" +
                "            \n" +
                "            websocket.onopen = function() {\n" +
                "                statusElement.textContent = 'Connected';\n" +
                "                statusElement.className = 'connection-status connected';\n" +
                "                showMessage('WebSocket connected successfully', 'success');\n" +
                "            };\n" +
                "            \n" +
                "            websocket.onmessage = function(event) {\n" +
                "                const message = JSON.parse(event.data);\n" +
                "                handleWebSocketMessage(message);\n" +
                "            };\n" +
                "            \n" +
                "            websocket.onclose = function() {\n" +
                "                statusElement.textContent = 'Disconnected';\n" +
                "                statusElement.className = 'connection-status disconnected';\n" +
                "                showMessage('WebSocket connection closed', 'error');\n" +
                "                \n" +
                "                // Attempt to reconnect after 3 seconds\n" +
                "                setTimeout(connectWebSocket, 3000);\n" +
                "            };\n" +
                "            \n" +
                "            websocket.onerror = function(error) {\n" +
                "                showMessage('WebSocket error: ' + error, 'error');\n" +
                "            };\n" +
                "        }\n" +
                "        \n" +
                "        function handleWebSocketMessage(message) {\n" +
                "            switch(message.type) {\n" +
                "                case 'file_content':\n" +
                "                    fileContentElement.value = message.content;\n" +
                "                    break;\n" +
                "                case 'file_updated':\n" +
                "                    fileContentElement.value = message.content;\n" +
                "                    showMessage('File updated by another client', 'info');\n" +
                "                    break;\n" +
                "                case 'write_success':\n" +
                "                case 'append_success':\n" +
                "                    showMessage(message.content, 'success');\n" +
                "                    break;\n" +
                "                case 'error':\n" +
                "                    showMessage(message.content, 'error');\n" +
                "                    break;\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function sendWebSocketMessage(action, data) {\n" +
                "            if (websocket && websocket.readyState === WebSocket.OPEN) {\n" +
                "                websocket.send(JSON.stringify({action: action, data: data}));\n" +
                "            } else {\n" +
                "                showMessage('WebSocket not connected', 'error');\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function readFile() {\n" +
                "            sendWebSocketMessage('read_file', '');\n" +
                "        }\n" +
                "        \n" +
                "        function writeFile() {\n" +
                "            const content = fileContentElement.value;\n" +
                "            sendWebSocketMessage('write_file', content);\n" +
                "        }\n" +
                "        \n" +
                "        function appendToFile() {\n" +
                "            const content = fileContentElement.value;\n" +
                "            sendWebSocketMessage('append_file', content);\n" +
                "        }\n" +
                "        \n" +
                "        function appendNewData() {\n" +
                "            const newData = document.getElementById('newData').value;\n" +
                "            if (newData.trim()) {\n" +
                "                sendWebSocketMessage('append_file', newData);\n" +
                "                document.getElementById('newData').value = '';\n" +
                "            }\n" +
                "        }\n" +
                "        \n" +
                "        function downloadFile() {\n" +
                "            window.location.href = '/filemanager?action=download';\n" +
                "        }\n" +
                "        \n" +
                "        function showMessage(message, type) {\n" +
                "            const messageDiv = document.createElement('div');\n" +
                "            messageDiv.className = 'status ' + type;\n" +
                "            messageDiv.textContent = message;\n" +
                "            messagesElement.appendChild(messageDiv);\n" +
                "            \n" +
                "            // Remove message after 5 seconds\n" +
                "            setTimeout(() => {\n" +
                "                if (messageDiv.parentNode) {\n" +
                "                    messageDiv.parentNode.removeChild(messageDiv);\n" +
                "                }\n" +
                "            }, 5000);\n" +
                "        }\n" +
                "        \n" +
                "        // Connect WebSocket when page loads\n" +
                "        window.onload = function() {\n" +
                "            connectWebSocket();\n" +
                "        };\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
    
    // Message class for JSON serialization
    public static class Message {
        public String type;
        public String content;
        public long timestamp;
        
        public Message() {}
        
        public Message(String type, String content) {
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters and setters for JSON serialization
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}