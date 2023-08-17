package bguspl.set.ex;

import bguspl.set.Env;

import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.*;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    private final Thread[] threads;


    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    private long[] playersFreezeTime;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.threads = new Thread[players.length];
        this.terminate = false;
        this.playersFreezeTime = new long[players.length];
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        // create players Threads
        for(int i=0; i < this.players.length; i++){
            this.threads[i] = new Thread(this.players[i], ""+i);
            this.threads[i].start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }

        announceWinners();
        terminatePlayers();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && (System.currentTimeMillis() < reshuffleTime  || env.config.turnTimeoutMillis == 0)) {
            sleepUntilWokenOrTimeout(); // TO ASK: does it efficient to make thread wait for 1 sec each time?
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();

        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        env.logger.log(Level.INFO, Thread.currentThread().getName() + ": Working on removeCardsFromTable " + System.currentTimeMillis()/1000);
        Queue<Integer> player_order = this.table.getQueue();
        if(!player_order.isEmpty()){
            Integer[][] tokenToSlot = this.table.getTokenToSlot();
            Integer[] slot = this.table.getSlot();
            while(!player_order.isEmpty()){
                boolean set_ruined = false;
                int player = player_order.poll(); 
                env.logger.log(Level.INFO, Thread.currentThread().getName() + ": Player(" + player + ") Entered Queue loop AT " + System.currentTimeMillis());
               
                synchronized(this.table.aiLock[player]){
                    int[] player_choices = new int[env.config.featureSize];
                    for(int i=0; i < env.config.featureSize; i++){
                        if(tokenToSlot[player][i] == null){
                            set_ruined = true;
                            continue;
                        }
                        if(slot[tokenToSlot[player][i]] == null){
                            this.table.removeToken(player, tokenToSlot[player][i]);
                            set_ruined = true;
                        }
                        else{
                            player_choices[i] = slot[tokenToSlot[player][i]];
                        }
                    }
                    if(set_ruined){
                        env.logger.log(Level.INFO, "[" + System.currentTimeMillis() + "]" + Thread.currentThread().getName() + ": Player(" + player + ") NOTIFIED INSIDE DEALER ");
                        this.table.aiLock[player].notifyAll();
                        continue;
                    }
                    boolean res = env.util.testSet(player_choices);
                    if(res){
                        for(int i=0; i < tokenToSlot[player].length; i++){
                            this.table.removeCard(tokenToSlot[player][i], true);
                            this.table.removeToken(player, tokenToSlot[player][i]);
                        }
                        
                        synchronized(this.players[player]){
                            synchronized(this.players[player].action){
                                this.players[player].setAction("point");
                            }
                            this.players[player].notifyAll();
                        }
                    }
                    else{
                        synchronized(this.players[player]){
                            synchronized(this.players[player].action){
                                this.players[player].setAction("penalty");
                            }
                            this.players[player].notifyAll();
                        }
                    }
                    // env.logger.log(Level.INFO, "["+System.currentTimeMillis()"] " + Thread.currentThread().getName() + ": Before NOTIFY (" + player + ")");
                    // this.table.aiLock[player].notifyAll();
                    // env.logger.log(Level.INFO, Thread.currentThread().getName() + ": After NOTIFY (" + player + ")");

                }
            }
            // check if there is another set available
            int cards_on_slot = 0;
            ArrayList<Integer> avlbl_deck = this.table.getAvlblDeck();
            for(int i=0; i< slot.length; i++){
                if(slot[i] != null){
                    cards_on_slot++;
                }
            }
            Integer[] slot_cards = new Integer[cards_on_slot];
            int j = 0;
            for(int i=0; i< slot.length; i++){
                if(slot[i] != null){
                    slot_cards[j] = slot[i];
                    j++;
                }
            }

            LinkedList<Integer> remaning_cards = new LinkedList<Integer>();
            for(int i = 0; i < slot_cards.length; i++){
                remaning_cards.add(slot_cards[i]);
            }
            remaning_cards.addAll(avlbl_deck);
            if(env.util.findSets(remaning_cards, 1).size() == 0){
                // end the game
                terminate();
            }
        }
    }
        

    private void terminatePlayers(){
        for(int i=this.players.length-1 ; i>=0; i--){
            synchronized(this.players[i]){
                this.players[i].terminate();
                this.players[i].notifyAll();
            }
            try {
                this.threads[i].join();
            } catch (InterruptedException ex_ignored) {}
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Integer[] slot = table.getSlot();
        boolean timer_reset = false;
        LinkedList<Integer> new_slot = new LinkedList<Integer>();
        LinkedList<Integer> cards_added = new LinkedList<Integer>();

        for(int i=0; i < slot.length; i++){
            if(slot[i] == null){
                int card = table.drawValidCard();
                if(card >= 0){
                    new_slot.add(card);
                    cards_added.add(card);
                }
                
            }
            else{
                new_slot.add(slot[i]);
            }
        }
        while(!terminate && env.config.turnTimeoutMillis <= 0 && env.util.findSets(new_slot, 1).size() == 0){
            for(int i=0; i < cards_added.size(); i++){
                this.table.addToAvlblDeck(cards_added.get(i));            
            }
            new_slot.clear();
            cards_added.clear();
            for(int i=0; i < slot.length; i++){
                if(slot[i] == null){
                    int card = table.drawValidCard();
                    if(card >= 0){
                        new_slot.add(card);
                        cards_added.add(card);
                    }
                }
                else{
                    new_slot.add(slot[i]);
                }
            }
        }
        int j = 0;  // @TODO handle the case when the there is no more cards to put and there is no set
        for(int i=0; i < slot.length; i++){
            if(slot[i] == null  && j < cards_added.size()){
                timer_reset = true;
                int card = cards_added.get(j);
                j++;
                if(card >= 0){
                    this.table.placeCard(card, i);
                }
            }
        }

        if(timer_reset){
            updateTimerDisplay(timer_reset);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized(this.table.dealerLock){
            try {this.table.dealerLock.wait(System.currentTimeMillis()%1000);} catch (InterruptedException ignored) {}
        }        
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(env.config.turnTimeoutMillis >= 0){
            if(env.config.turnTimeoutMillis == 0){
                if(reset){
                    reshuffleTime = System.currentTimeMillis();
                }
                this.env.ui.setCountdown(System.currentTimeMillis() - reshuffleTime, false);
            }
            else{
                if(reset){
                    reshuffleTime = System.currentTimeMillis() + this.env.config.turnTimeoutMillis;
                }
                boolean warning = false;
                if(reshuffleTime-System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis){
                    warning = true;
                }
                this.env.ui.setCountdown(reshuffleTime-System.currentTimeMillis(), warning);
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    public void removeAllCardsFromTable() {
        Integer[] slot = this.table.getSlot(); 
        for(int i=0; i < slot.length; i++){
            if(slot[i] != null){
                table.removeCard(i, false);
            }
        }

        this.table.removeTokens();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    public void announceWinners() {
        LinkedList<Integer> winners = new LinkedList<Integer>();
        int max_score = 0;
        for(Player p: players){
            if (p.score() > max_score){
                max_score=p.score();
                winners.clear();
                winners.add(p.id);
            }
            else if (p.score() == max_score){
                winners.add(p.id);
            }
        }
        int[] real_winners = new int[winners.size()];
        for (int i = 0; i < real_winners.length; i++) {
            real_winners[i] = winners.get(i);
        }
        this.env.ui.announceWinner(real_winners);      
    }
}  // @TODO terminate aI thread, also terminate gracefully when clicking "X"
