import socket as s
c=s.socket();c.connect(('127.0.0.1',12000))
while 1:
 t=input("Enter expression or HTTP GET request (type 'exit' to quit): ")
 if t=='exit':break
 if t[:3]!='GET':continue
 c.send((t+'\r\nHost:127.0.0.1\r\n\r\n').encode())
 c.settimeout(0.3);r=b''
 try:
  while 1:
   b=c.recv(1024)
   if not b:break
   r+=b
 except:0
 print("404 File Not Found\r\n"if"404"in r.decode()else r.decode().split('\r\n\r\n')[-1])
