package bankster.client.domain;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Implementation of Spring Security UserDetails interface
 *
 * @author joudahidri
 */
public class UserDetailsImpl implements UserDetails {
	/**
	 * Serial version id
	 */
	private static final long serialVersionUID = 1L;
	private User user;

	public UserDetailsImpl(User user) {
		this.setUser(user);
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// TODO return user role when roles implemented
		return null;
	}

	@Override
	public String getPassword() {
		return user.getPassword();
	}

	@Override
	public String getUsername() {
		return user.getUsername();
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return true;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

}
