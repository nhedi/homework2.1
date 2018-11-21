package server.controller;

import server.model.Game;
import java.io.IOException;
/**
 *
 * @author yuchen
 */
public class Controller {
    private final Game game = new Game();
  
    public void appendToHistory(String msg) {
       game.appendEntry(msg);
    }
    
    public String[] getGameStatus() {
        return game.getGameStatus();
    }
    
    public void playGame(String guess) {
        game.playGame(guess);
    }

    public String showCurrentState() { return game.showCurrentState(); }
    
    public int remainingGuesses() { return game.remainingGuesses(); }
    
    public boolean correctWord() { return game.correctWord(); }
    
    public String getWord() { return game.word; }
    
    public int score() { return game.score; }

    public void selectedWord() {
        try {
           game.selectedWord();
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
    }
}
