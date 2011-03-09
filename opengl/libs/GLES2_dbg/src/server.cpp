/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <sys/ioctl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <pthread.h>

#include "header.h"

namespace android
{

int serverSock = -1, clientSock = -1;

int timeMode = SYSTEM_TIME_THREAD;

void StopDebugServer();

static void Die(const char * msg)
{
    LOGD("\n*\n*\n* GLESv2_dbg: Die: %s \n*\n*", msg);
    StopDebugServer();
    exit(1);
}

void StartDebugServer()
{
    LOGD("GLESv2_dbg: StartDebugServer");
    if (serverSock >= 0)
        return;

    LOGD("GLESv2_dbg: StartDebugServer create socket");
    struct sockaddr_in server = {}, client = {};

    /* Create the TCP socket */
    if ((serverSock = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP)) < 0) {
        Die("Failed to create socket");
    }
    /* Construct the server sockaddr_in structure */
    server.sin_family = AF_INET;                  /* Internet/IP */
    server.sin_addr.s_addr = htonl(INADDR_ANY);   /* Incoming addr */
    server.sin_port = htons(5039);       /* server port */

    /* Bind the server socket */
    socklen_t sizeofSockaddr_in = sizeof(sockaddr_in);
    if (bind(serverSock, (struct sockaddr *) &server,
             sizeof(server)) < 0) {
        Die("Failed to bind the server socket");
    }
    /* Listen on the server socket */
    if (listen(serverSock, 1) < 0) {
        Die("Failed to listen on server socket");
    }

    LOGD("server started on %d \n", server.sin_port);


    /* Wait for client connection */
    if ((clientSock =
                accept(serverSock, (struct sockaddr *) &client,
                       &sizeofSockaddr_in)) < 0) {
        Die("Failed to accept client connection");
    }

    LOGD("Client connected: %s\n", inet_ntoa(client.sin_addr));
//    fcntl(clientSock, F_SETFL, O_NONBLOCK);

    glesv2debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_function(glesv2debugger::Message_Function_ACK);
    msg.set_type(glesv2debugger::Message_Type_Response);
    msg.set_expect_response(false);
    Send(msg, cmd);
}

void StopDebugServer()
{
    LOGD("GLESv2_dbg: StopDebugServer");
    if (clientSock > 0) {
        close(clientSock);
        clientSock = -1;
    }
    if (serverSock > 0) {
        close(serverSock);
        serverSock = -1;
    }

}

void Receive(glesv2debugger::Message & cmd)
{
    unsigned len = 0;

    int received = recv(clientSock, &len, 4, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response length");
    else if (4 != received) {
        LOGD("received %dB: %.8X", received, len);
        Die("Received length mismatch, expected 4");
    }
    len = ntohl(len);
    static void * buffer = NULL;
    static unsigned bufferSize = 0;
    if (bufferSize < len) {
        buffer = realloc(buffer, len);
        ASSERT(buffer);
        bufferSize = len;
    }
    received = recv(clientSock, buffer, len, MSG_WAITALL);
    if (received < 0)
        Die("Failed to receive response");
    else if (len != received)
        Die("Received length mismatch");
    cmd.Clear();
    cmd.ParseFromArray(buffer, len);
}

float Send(const glesv2debugger::Message & msg, glesv2debugger::Message & cmd)
{
    static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_lock(&mutex); // TODO: this is just temporary

    static std::string str;
    const_cast<glesv2debugger::Message &>(msg).set_context_id(pthread_self());
    msg.SerializeToString(&str);
    unsigned len = str.length();
    len = htonl(len);
    int sent = -1;
    sent = send(clientSock, (const char *)&len, sizeof(len), 0);
    if (sent != sizeof(len)) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, sizeof(len), clientSock);
        Die("Failed to send message length");
    }
    nsecs_t c0 = systemTime(timeMode);
    sent = send(clientSock, str.c_str(), str.length(), 0);
    float t = (float)ns2ms(systemTime(timeMode) - c0);
    if (sent != str.length()) {
        LOGD("actual sent=%d expected=%d clientSock=%d", sent, str.length(), clientSock);
        Die("Failed to send message");
    }

    if (!msg.expect_response()) {
        pthread_mutex_unlock(&mutex);
        return t;
    }

    Receive(cmd);

    //LOGD("Message sent tid=%lu len=%d", pthread_self(), str.length());
    pthread_mutex_unlock(&mutex);
    return t;
}

void SetProp(const glesv2debugger::Message & cmd)
{
    switch (cmd.prop()) {
    case glesv2debugger::Message_Prop_Capture:
        LOGD("SetProp Message_Prop_Capture %d", cmd.arg0());
        capture = cmd.arg0();
        break;
    case glesv2debugger::Message_Prop_TimeMode:
        LOGD("SetProp Message_Prop_TimeMode %d", cmd.arg0());
        timeMode = cmd.arg0();
        break;
    default:
        assert(0);
    }
}

int * MessageLoop(FunctionCall & functionCall, glesv2debugger::Message & msg,
                  const bool expectResponse, const glesv2debugger::Message_Function function)
{
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
    const int * ret = 0;
    glesv2debugger::Message cmd;
    msg.set_context_id(0);
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    msg.set_expect_response(expectResponse);
    msg.set_function(function);
    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            ret = functionCall(_c, msg);
            if (!msg.has_time()) // some has output data copy, so time inside call
                msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(0);
            msg.set_function(function);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            Send(msg, cmd);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return const_cast<int *>(ret);
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(cmd);
            Receive(cmd);
            break;
        default:
            ASSERT(0); //GenerateCall(msg, cmd);
            break;
        }
    }
    return 0;
}
}; // namespace android {