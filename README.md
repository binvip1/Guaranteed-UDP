# Guaranteed-UDP

This project, developed as part of the *Advanced Internetworking* course at **KTH Royal Institute of Technology**, focuses on enhancing file transmission capabilities. The application facilitates the simultaneous transmission of multiple packets, ensuring the integrity of transferred files. Implemented using Java multithreading, the application supports sending and receiving files without corruption.

### Usage
- **Receiver:** `java VSRecv [-o] port` (Use `-o` to overwrite the existing file)
- **Sender:** `java VSSend host1:port1 [host2:port2] ... file1 [file2]...`

The core functionality of the project resides in the `GUDPSocket` file. To ensure reliable delivery and proper ordering, the project incorporates the following mechanisms into the standard UDP protocol:

- **Sliding Window Flow Control:** Facilitates efficient data flow management by controlling the number of unacknowledged packets.
- **Detection and ARQ-Based Retransmission:** Detects lost or reordered packets and triggers automatic retransmission using Automatic Repeat reQuest (ARQ) protocols.
