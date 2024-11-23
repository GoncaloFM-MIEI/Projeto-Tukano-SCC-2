package tukano.impl.data;

import tukano.impl.data.Following;

/**
 * Represents a Follow, as stored in the database
 */
public class FollowingDAO extends Following {
    private String _rid;
    public String get_rid() {
        return _rid;
    }


    public void set_rid(String _rid) {
        this._rid = _rid;
    }


    public String get_ts() {
        return _ts;
    }


    public void set_ts(String _ts) {
        this._ts = _ts;
    }


    private String _ts;

    public FollowingDAO() {
    }

    @Override
    public String toString() {
        return "FollowingDAO [_rid=" + _rid + ", _ts=" + _ts + ", following=" + super.toString();
    }
}
