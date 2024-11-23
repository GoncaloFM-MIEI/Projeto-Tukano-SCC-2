package tukano.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name="users")
public class User {

	@Id
	@JsonProperty("id")
	private String id;
	private String pwd;
	private String email;	
	private String displayName;

	public User() {}
	
	public User(String userId, String pwd, String email, String displayName) {
		this.id = userId;
		this.pwd = pwd;
		this.email = email;
		this.displayName = displayName;
	}

	public String getUserId() {
		return id;
	}
	public void setUserId(String id) {
		this.id = id;
	}
	public String getPwd() {
		return pwd;
	}
	public void setPwd(String pwd) {
		this.pwd = pwd;
	}
	public String getEmail() {
		return email;
	}
	public void setEmail(String email) {
		this.email = email;
	}
	public String getDisplayName() {
		return displayName;
	}
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	
	public String userId() {
		return id;
	}
	
	public String pwd() {
		return pwd;
	}
	
	public String email() {
		return email;
	}
	
	public String displayName() {
		return displayName;
	}
	
	@Override
	public String toString() {
		return "User [id=" + id + ", pwd=" + pwd + ", email=" + email + ", displayName=" + displayName + "]";
	}
	
	public User copyWithoutPassword() {
		return new User(id, "", email, displayName);
	}
	
	public User updateFrom( User other ) {
		return new User( id,
				other.pwd != null ? other.pwd : pwd,
				other.email != null ? other.email : email, 
				other.displayName != null ? other.displayName : displayName);
	}
}
