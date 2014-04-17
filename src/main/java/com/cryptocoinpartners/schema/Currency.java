package com.cryptocoinpartners.schema;


import com.cryptocoinpartners.util.PersistUtil;

import javax.persistence.Entity;
import javax.persistence.EntityNotFoundException;


/**
 * @author Tim Olson
 */
@SuppressWarnings( "UnusedDeclaration" )
@Entity
public class Currency extends Fungible {

    public boolean isFiat() { return fiat; }


    public static Currency forSymbol( String symbol ) {
        return PersistUtil.queryOne(Currency.class, "select c from Currency c where symbol=?1", symbol);
    }


    // JPA
    protected Currency() {}
    protected void setFiat(boolean fiat) { this.fiat = fiat; }


    // used by Currencies
    static Currency forSymbolOrCreate( String symbol, boolean isFiat )
    {
        if( PersistUtil.generatingDefaultData )
            return new Currency(isFiat, symbol);
        try {
            return forSymbol(symbol);
        }
        catch( EntityNotFoundException e ) {
            final Currency currency = new Currency(isFiat, symbol);
            PersistUtil.insert(currency);
            return currency;
        }
    }


    private Currency(boolean fiat, String symbol) {
        super(symbol);
        this.fiat = fiat;
    }


    private boolean fiat;
}
