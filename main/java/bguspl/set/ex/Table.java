package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Random;
import java.util.*;
import java.util.logging.Level;




/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    protected  ArrayList<Integer> avlb_deck; // card on deck

    protected final Integer[][] tokenToSlot;

    Queue<Integer> player_order;

    public Object dealerLock;

    public Object[] aiLock;
    public String[] actions;


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.avlb_deck = new ArrayList<Integer>();
        for(Integer i=0;i<env.config.deckSize;i++){
            this.avlb_deck.add(i);
        }
        this.tokenToSlot = new Integer[env.config.players][env.config.featureSize];
        this.player_order = new LinkedList<Integer>();

        dealerLock = new Object();
        aiLock = new Object[env.config.players];
        actions = new String[env.config.players];
        for(int i=0;i<aiLock.length;i++){
            aiLock[i] = new Object();
            actions[i] = "";
        }

    
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;

        this.env.ui.placeCard(card, slot);
        // avlb_deck.remove(card);
    }

    public void setAction(int player_id, String a){
        synchronized(actions[player_id]){
            this.actions[player_id] = a;
        }
    }

    public String getAction(int player){
        synchronized(this.actions[player]){
            String action = this.actions[player];
            this.actions[player] = "";
            return action;
        }
    }


    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot, boolean is_set) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        if(!is_set){
            avlb_deck.add(slotToCard[slot]);
        }
        cardToSlot[slotToCard[slot]] = null;
        slotToCard[slot] = null;

        this.env.ui.removeCard(slot);

    }


    public int drawValidCard(){
        if(avlb_deck.size() == 1){
            int card = avlb_deck.get(0);
            avlb_deck.remove(0);
            return card;
        }
        else if(avlb_deck.size() == 0){
            return -1;
        }
        Random rand = new Random();
        int card_id = rand.nextInt(avlb_deck.size());
        int card = avlb_deck.get(card_id);
        avlb_deck.remove(card_id);
        return card;
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized void placeToken(int player, int slot) {
        int counter = 0;
        boolean inserted = false;
        for(int i=0; i < tokenToSlot[player].length; i++){
            if(tokenToSlot[player][i] == null && !inserted){
                tokenToSlot[player][i] = slot;
                this.env.ui.placeToken(player, slot);
                inserted = true;
                counter++;
            }
            else if(tokenToSlot[player][i] != null){
                counter++;
            }
        }

        if(counter == env.config.featureSize){
            synchronized(this.player_order){
                env.logger.log(Level.INFO, Thread.currentThread().getName() + "Adding Player " + player + " to Queue");

                this.player_order.add(player);
            }
            synchronized(this.dealerLock){
                this.dealerLock.notifyAll();
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        for(int i=0; i < tokenToSlot[player].length; i++){
            if(tokenToSlot[player][i] != null && tokenToSlot[player][i] == slot){
                tokenToSlot[player][i] = null;
                this.env.ui.removeToken(player, slot);
            }
        }
        return false;
    }

    public Integer[] getSlot(){
        return slotToCard;
    }

    public Queue<Integer> getQueue(){
        return player_order;
    }

    public Integer[][] getTokenToSlot(){
        return tokenToSlot;
    }

    public ArrayList<Integer> getAvlblDeck(){
        return avlb_deck;
    }

    public void addToAvlblDeck(Integer to_add){
        avlb_deck.add(to_add);
    }

    public void removeTokens(){
        for(int i=0; i < env.config.players; i++){
            tokenToSlot[i] = new Integer[env.config.featureSize];
        }

        this.env.ui.removeTokens();
    }
}
