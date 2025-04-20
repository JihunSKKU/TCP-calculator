#include <iostream>
#include <string>
#include <winsock2.h>
#pragma comment(lib, "ws2_32.lib")

using namespace std;

int main() {
    // Initialize Winsock
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2,2), &wsaData) != 0) {
        cerr << "WSAStartup failed." << endl;
        return 1;
    }

    // Create socket
    SOCKET sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock == INVALID_SOCKET) {
        cerr << "Socket creation failed." << endl;
        WSACleanup();
        return 1;
    }

    // Set server address (localhost:12000)
    sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(12000);
    serverAddr.sin_addr.s_addr = inet_addr("127.0.0.1");

    // Connect to the server
    if (connect(sock, (sockaddr*)&serverAddr, sizeof(serverAddr)) < 0) {
        cerr << "Connection failed." << endl;
        closesocket(sock);
        WSACleanup();
        return 1;
    }

    while (true) {
        cout << "Enter expression or HTTP GET request (type 'exit' to quit): ";
        string expression;
        getline(cin, expression);

        if (expression == "exit" || expression.empty()) break;

        // Case: Math Expression Request
        if (expression.substr(0, 3) != "GET") {
            if (expression.back() != '\n') expression.push_back('\n');

            int sendResult = send(sock, expression.c_str(), expression.size(), 0);
            if (sendResult == SOCKET_ERROR) {
                cerr << "Send failed." << endl;
                break;
            }

            char buffer[1024];
            int bytesReceived = recv(sock, buffer, sizeof(buffer) - 1, 0);
            if (bytesReceived > 0) {
                buffer[bytesReceived] = '\0';
                cout << "Server response:\n" << buffer << endl;
            } else {
                cerr << "No response from server." << endl;
            }
        }

        // Case: HTTP GET Request
        else {
            string getRequest = expression + "\r\nHost:127.0.0.1\r\n\r\n";

            int sendResult = send(sock, getRequest.c_str(), getRequest.size(), 0);
            if (sendResult == SOCKET_ERROR) {
                cerr << "Send failed." << endl;
                break;
            }

            string response;
            char buffer[1024];
            int bytesReceived;
            while ((bytesReceived = recv(sock, buffer, sizeof(buffer) - 1, 0)) > 0) {
                buffer[bytesReceived] = '\0';
                response += buffer;
                if (bytesReceived < 1023) break;
            }

            // Check if file exists or not
            if (response.find("404") != string::npos) {
                cout << "404 File Not Found\r\n" << endl;
            } else {
                size_t pos = response.find("\r\n\r\n");
                if (pos != string::npos) {
                    cout << response.substr(pos + 4) << endl;
                } else {
                    cout << response << endl;
                }
            }
        }
    }

    // Clean up and close
    closesocket(sock);
    WSACleanup();
    return 0;
}
