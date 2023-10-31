# Guaranteed UDP

This project, developed as part of the *Advanced Internetworking* course at **KTH Royal Institute of Technology**, focuses on enhancing file transmission capabilities. By implementing Guaranteed UDP, the application can send and receive files without corruption. Utilizing Java multithreading, the application achieves efficient handling of multiple packets in flight.

### Usage
- **Receiver:** `java VSRecv [-o] port` (Use `-o` to overwrite the existing file)
- **Sender:** `java VSSend host1:port1 [host2:port2] ... file1 [file2]...`

### Functionalities
The key components of the project resides in the `GUDPSocket` file. To ensure reliable delivery and proper ordering, the project incorporates the following mechanisms into the standard UDP protocol:

- **Sliding Window Flow Control:** Facilitates efficient data flow management by controlling the number of unacknowledged packets.
- **Detection and ARQ-Based Retransmission:** Detects lost or reordered packets and triggers automatic retransmission using Automatic Repeat reQuest (ARQ) protocols.
