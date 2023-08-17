package bguspl.set.ex;

import java.util.Random;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    public long freezeEndTime;

    // public Object aiLock;
    
    public String action;



    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.score = 0;
        this.terminate = false;
        this.freezeEndTime = 0;
        // this.aiLock = new Object();
        this.action = "";
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            synchronized(this){
                try { 
                    env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " BEFORE PLAYER RUN WAIT.");
                    this.wait();
                    env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " AFTER PLAYER RUN WAIT.");
                } catch (InterruptedException ignored) {}
            }
            doAction();
            
        }
        synchronized(this.table.aiLock[id]){
            this.table.aiLock[id].notifyAll();
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    private void doAction(){
        String action = this.table.getAction(id);
        // synchronized(action){
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + ": Player " + id + " action = " + action);
        if(action == "point"){
            this.point();
        }
        else if(action == "penalty"){
            this.penalty();
        }
        // }
    }

    public void setAction(String a){
        this.table.setAction(id, a);
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                synchronized (this.table.aiLock[id]){
                    cancelPrevSelection();
                    env.logger.log(Level.INFO, Thread.currentThread().getName() + ": After Cancel selection");

                    aiSelection();
                    env.logger.log(Level.INFO, Thread.currentThread().getName() + ": After selection");

                    try {
                        env.logger.log(Level.INFO, "["+System.currentTimeMillis() +"] " + Thread.currentThread().getName() + ": Before Wait");

                        this.table.aiLock[id].wait();
                        this.sleepIfNeeded();
                        env.logger.log(Level.INFO, "["+System.currentTimeMillis() +"] " + Thread.currentThread().getName() + ": After Wait");

                    } catch (InterruptedException ignored) {}
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    private void sleepIfNeeded(){
        if(System.currentTimeMillis() < this.freezeEndTime){
            try {
                Thread.sleep(this.freezeEndTime - System.currentTimeMillis());
            } catch (InterruptedException ignored) {}
        }
    }

    public void cancelPrevSelection(){
        Integer[][] tokenToSlot = this.table.getTokenToSlot();
        Integer[] slots = tokenToSlot[id];
        for(int i=0; i<slots.length; i++){
            if(slots[i] != null)
                this.table.removeToken(id, slots[i]);
        }
    }

    public void aiSelection(){
        if(this.freezeEndTime <= System.currentTimeMillis()){
            Random rand = new Random();
            int s1 = rand.nextInt(env.config.tableSize);
            this.keyPressed(s1);
            int s2 = rand.nextInt(env.config.tableSize);
            while(s2 == s1){
                s2 = rand.nextInt(env.config.tableSize);
            }
            this.keyPressed(s2);

            int s3 = rand.nextInt(env.config.tableSize);
            while(s3 == s1 || s3 == s2){
                s3 = rand.nextInt(env.config.tableSize);
            }
            this.keyPressed(s3);
        }

    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        this.terminate=true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(this.freezeEndTime <= System.currentTimeMillis()){
            Integer[][] tokenToSlot = this.table.getTokenToSlot();
            boolean exists = false;
            for(int i=0; i < tokenToSlot[id].length; i++){
                if(tokenToSlot[id][i] != null && tokenToSlot[id][i] == slot){
                    exists = true;
                    break;
                }
            }
            if(exists){
                this.table.removeToken(id, slot);
            }
            else{
                env.logger.log(Level.INFO, Thread.currentThread().getName() + " Player " + id + " Placing Token On Slot " + slot);
                this.table.placeToken(id, slot);
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        score++;

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, score);
        long freezeTime = this.env.config.pointFreezeMillis;
        env.logger.log(Level.INFO, Thread.currentThread().getName() + " Point for Player-ID " + id);
        this.freezePLayer(freezeTime);

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        long freezeTime = this.env.config.penaltyFreezeMillis;
        // env.logger.log(Level.INFO, Thread.currentThread().getName() + ": After penalty1");
        this.freezePLayer(freezeTime);
        env.logger.log(Level.INFO, Thread.currentThread().getName() + ": After penalty");


    }

    private void freezePLayer(long freezeTime){
        env.logger.log(Level.INFO, "["+System.currentTimeMillis() +"] " + Thread.currentThread().getName() + ": INSIDE FREEZE (" + id + ")");
        this.freezeEndTime = System.currentTimeMillis() + freezeTime;
        long timer = freezeTime;
        while(timer >= 1000){
            this.env.ui.setFreeze(id, timer);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex_ignored) {}
            timer = timer-1000;
        }
        try {
            Thread.sleep(timer);
        } catch (InterruptedException ex_ignored) {}

        this.env.ui.setFreeze(id, 0);
        env.logger.log(Level.INFO, "["+System.currentTimeMillis() +"] " + Thread.currentThread().getName() + ": Before SYNC FREEZE (" + id + ")");
        synchronized(this.table.aiLock[id]){
            env.logger.log(Level.INFO, "["+System.currentTimeMillis() +"] " + Thread.currentThread().getName() + ": Before NOTIFY FREEZE (" + id + ")");
            this.table.aiLock[id].notifyAll();
            
        }
    }

    public int score() {
        return score;
    }

    public boolean isHuman(){
        return human;
    }
} // @TODO deal with while loop in run method
