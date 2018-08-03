package com.soriole.dfsnode.service;

import com.soriole.dfsnode.model.db.ClientData;
import com.soriole.dfsnode.model.db.Transaction;
import com.soriole.dfsnode.model.dto.ClientDataDto;
import com.soriole.dfsnode.model.dto.NodeDetails;
import com.soriole.dfsnode.model.dto.NodeTransactionDetails;
import com.soriole.dfsnode.model.dto.TransactionDto;
import com.soriole.dfsnode.repository.ClientDataRepository;
import com.soriole.dfsnode.repository.ClientRepository;
import com.soriole.dfsnode.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * @author github.com/bipinkh
 * created on : 26 Jul 2018
 */
@Service
public class NodeService {

    @Autowired
    ClientRepository clientRepository;
    @Autowired
    ClientDataRepository clientDataRepository;
    @Autowired
    TransactionRepository transactionRepository;

    //  get stats
    public ResponseEntity<NodeDetails> getStats() {

        Calendar calendar = Calendar.getInstance();
        Timestamp currentTimeStamp = new Timestamp(calendar.getTime().getTime());

        long totalFileReceived = clientDataRepository.count();
        long totalFileDownloaded = 0;
        try {
            totalFileDownloaded = clientDataRepository.getSumOfDownloads();
        }catch (Exception e){
        }

        double totalStorageProvided= 0;
        List<String> allFilePaths = new ArrayList<>();
        try{
            allFilePaths = clientDataRepository.getALlFileDataPath();
            for (String filepath : allFilePaths){
                File file = new File(filepath);
                totalStorageProvided += file.length();
            }
            totalStorageProvided /= (1024*1024);
        } catch (Exception e){
            System.out.println("failed to calculate the totaldownloads");
        }



        long totalClients = clientRepository.count();

        return ResponseEntity.ok(
                new NodeDetails(totalFileReceived, totalFileDownloaded, totalStorageProvided,totalClients)
        );
    }


    // get all transaction details
    public ResponseEntity<NodeTransactionDetails> getTxns() {
        NodeTransactionDetails nodeTransactionDetails = new NodeTransactionDetails();
        //todo: get tokens
        nodeTransactionDetails.setAvailableTokens(0);
        nodeTransactionDetails.setLockedDepositTokens(0);
        nodeTransactionDetails.setLockedEarningTokens(0);
        // get transactions list
        List<Transaction> transactions = transactionRepository.findAll();
        for (Transaction transaction: transactions)
            nodeTransactionDetails.addTransactionDto(TransactionDto.fromTransaction(transaction));

        return ResponseEntity.status(HttpStatus.OK).body(nodeTransactionDetails);
    }


    // get all file details
    public List<ClientDataDto> getFiles() {
        List<ClientDataDto> returnList = new ArrayList<>();

        try {
            List<ClientData> clientData = clientDataRepository.findAll();
            for (ClientData data : clientData) {
                returnList.add(ClientDataDto.fromClientData(data));
            }
        }catch (Exception e){
        }
        return returnList;
    }
}
